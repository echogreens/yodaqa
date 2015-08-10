package cz.brmlab.yodaqa.provider.rdf;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.google.gson.stream.JsonReader;
import com.google.gson.Gson;
import com.hp.hpl.jena.rdf.model.Literal;

import org.slf4j.Logger;

/** A wrapper around DBpedia "Titles" dataset that maps titles to
 * Wikipedia articles. The main point of using this is to find out
 * whether an article with the given title (termed also "label")
 * exists.
 *
 * Articles are represented as (label, pageId), where label is the
 * article title. The label is included as we pass through redirects
 * and disambiguation pages. */

public class DBpediaTitles extends DBpediaLookup {
	/** A container of enwiki article metadata.
	 * This must 1:1 map to label-lookup API. */
	public class Article {
		protected String name;
		protected int pageID;
		protected String matchedLabel;
		protected String canonLabel;
		protected int dist; // edit dist.

		public Article(String label, int pageID) {
			this.matchedLabel = label;
			this.canonLabel = label;
			this.pageID = pageID;
		}

		public Article(String label, int pageID, String name, int dist) {
			this(label, pageID);
			this.name = name;
			this.dist = dist;
		}

		public String getName() { return name; }
		public int getPageID() { return pageID; }
		public String getMatchedLabel() { return matchedLabel; }
		public String getCanonLabel() { return canonLabel; }
		public int getDist() { return dist; }
	}

	/** Query for a given title, returning a set of articles. */
	public List<Article> query(String title, Logger logger) {
		for (String titleForm : cookedTitles(title)) {
			List<Article> results = queryTitleForm(titleForm, logger);
			if (!results.isEmpty())
				return results;
		}
		return new ArrayList<Article>();
	}

	/** Query for a given specific title form, returning a set
	 * of articles. */
	public List<Article> queryTitleForm(String title, Logger logger) {
		/* XXX: Case-insensitive search via SPARQL turns out
		 * to be surprisingly tricky.  Cover 91% of all cases
		 * by capitalizing words that are not stopwords  */
		boolean wasCapitalized = Character.toUpperCase(title.charAt(0)) == title.charAt(0);
		title = super.capitalizeTitle(title);

		title = title.replaceAll("\"", "").replaceAll("\\\\", "").replaceAll("\n", " ");

		String rawQueryStr =
			"{\n" +
			   // (A) fetch resources with @title label
			"  ?res rdfs:label \"" + title + "\"@en.\n" +
			"} UNION {\n" +
			   // (B) fetch also resources targetted by @title redirect
			"  ?redir dbo:wikiPageRedirects ?res .\n" +
			"  ?redir rdfs:label \"" + title + "\"@en .\n" +
			"} UNION {\n" +
			   // (C) fetch also resources targetted by @title disambiguation
			"  ?disamb dbo:wikiPageDisambiguates ?res .\n" +
			"  ?disamb rdfs:label \"" + title + "\"@en .\n" +
			"}\n" +
			 // for (B) and (C), we are also getting a redundant (A) entry;
			 // identify the redundant (A) entry by filling
			 // ?redirTarget in that case
			"OPTIONAL { ?res dbo:wikiPageRedirects ?redirTarget . }\n" +
			"OPTIONAL { ?res dbo:wikiPageDisambiguates ?disambTarget . }\n" +
			 // set the output variables
			"?res dbo:wikiPageID ?pageID .\n" +
			"?res rdfs:label ?label .\n" +

			 // ignore the redundant (A) entries (redirects, disambs)
			"FILTER ( !BOUND(?redirTarget) )\n" +
			"FILTER ( !BOUND(?disambTarget) )\n" +
			 // weed out categories and other in-namespace junk
			 // FIXME: this also covers X-Men:.*, 2001:.*, ... movie titles
			"FILTER ( !regex(str(?res), '^http://dbpedia.org/resource/[^_]*:', 'i') )\n" +
			 // output only english labels, thankyouverymuch
			"FILTER ( LANG(?label) = 'en' )\n" +
			"";
		//logger.debug("executing sparql query: {}", rawQueryStr);
		List<Literal[]> rawResults = rawQuery(rawQueryStr,
			new String[] { "pageID", "label" }, 0);

		List<Article> results = new ArrayList<Article>(rawResults.size());
		for (Literal[] rawResult : rawResults) {
			String label = rawResult[1].getString();
			/* Undo capitalization if the label isn't all-caps
			 * and the original title wasn't capitalized either.
			 * Otherwise, all our terms will end up all-caps,
			 * a silly thing. */
			if (!wasCapitalized && (label.length() > 1 && Character.toUpperCase(label.charAt(1)) != label.charAt(1)))
				label = Character.toLowerCase(label.charAt(0)) + label.substring(1);
			logger.debug("DBpedia {}: [[{}]]", title, label);
	//		results.add(new Article(label, rawResult[0].getInt()));
		}
		for (Article a: queryLabelLookup(title)) {
			System.out.println(a.getCanonLabel() + " " + a.getPageID());
			results.add(a);
		}

		return results;

		/*
		return rawQuery("?res rdfs:label \"" + title + "\"@en.\n" +
				"?res dbo:wikiPageID ?pageid\n" +
				// weed out categories and other in-namespace junk
				"FILTER ( !regex(str(?res), '^http://dbpedia.org/resource/[^_]*:', 'i') )\n",
				"pageid");
				*/

	}

	/**
	 * Query a label-lookup service (fuzzy search) for a concept label.
	 * We use https://github.com/brmson/label-lookup/ as a fuzzy search
	 * that's tolerant to wrong capitalization, omitted interpunction
	 * and typos; we get the enwiki article metadata from here.
	 *
	 * XXX: This method should probably be in a different
	 * provider subpackage altogether... */
	public synchronized List<Article> queryLabelLookup(String label) {
		List<Article> results = new LinkedList<>();
		try {
			String encodedName = URLEncoder.encode(label, "UTF-8").replace("+", "%20");
			String requestURL = "http://dbp-labels.ailao.eu:5000/search/" + encodedName;
			URL request = new URL(requestURL);
			URLConnection connection = request.openConnection();
			Gson gson = new Gson();
			JsonReader jr = new JsonReader(new InputStreamReader(connection.getInputStream()));

			jr.beginObject();
				jr.nextName(); //results :
				jr.beginArray();
				while (jr.hasNext()) {
					Article o = gson.fromJson(jr, Article.class);
					if (results.isEmpty()) // XXX: Only pick the single nearest concept
						results.add(o);
					logger.debug("Server returned: d{} ~{} [{}] {} {}", o.getDist(), o.getMatchedLabel(), o.getCanonLabel(), o.getName(), o.getPageID());
				}
				jr.endArray();
			jr.endObject();

		} catch (IOException e) {
			// FIXME: Retry mechanism.
			e.printStackTrace();
			return results;
		}
		return results;
	}
}

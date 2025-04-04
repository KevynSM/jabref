package org.jabref.logic.importer.fetcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jabref.logic.help.HelpFile;
import org.jabref.logic.importer.ImporterPreferences;
import org.jabref.logic.importer.PagedSearchBasedParserFetcher;
import org.jabref.logic.importer.Parser;
import org.jabref.logic.importer.fetcher.transformers.SpringerQueryTransformer;
import org.jabref.logic.os.OS;
import org.jabref.logic.util.URLUtil;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;
import org.jabref.model.entry.Month;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import com.google.common.base.Strings;
import kong.unirest.core.json.JSONArray;
import kong.unirest.core.json.JSONObject;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.lucene.queryparser.flexible.core.nodes.QueryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches data from the Springer
 *
 * @see <a href="https://dev.springernature.com/">API documentation</a> for more details
 */
public class SpringerFetcher implements PagedSearchBasedParserFetcher, CustomizableKeyFetcher {
    public static final String FETCHER_NAME = "Springer";

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringerFetcher.class);

    private static final String API_URL = "https://api.springernature.com/meta/v1/json";
    // Springer query using the parameter 'q=doi:10.1007/s11276-008-0131-4s=1' will respond faster
    private static final String TEST_URL_WITHOUT_API_KEY = "https://api.springernature.com/meta/v1/json?q=doi:10.1007/s11276-008-0131-4s=1&p=1&api_key=";

    private final ImporterPreferences importerPreferences;

    public SpringerFetcher(ImporterPreferences importerPreferences) {
        this.importerPreferences = importerPreferences;
    }

    /**
     * Convert a JSONObject obtained from <a href="http://api.springer.com/metadata/json">http://api.springer.com/metadata/json</a> to a BibEntry
     *
     * @param springerJsonEntry the JSONObject from search results
     * @return the converted BibEntry
     */
    public static BibEntry parseSpringerJSONtoBibtex(JSONObject springerJsonEntry) {
        // Fields that are directly accessible at the top level Json object
        Field[] singleFieldStrings = {StandardField.ISSN, StandardField.VOLUME, StandardField.ABSTRACT, StandardField.DOI, StandardField.TITLE, StandardField.NUMBER,
                StandardField.PUBLISHER};

        BibEntry entry = new BibEntry();
        Field nametype;

        // Guess publication type
        String isbn = springerJsonEntry.optString("isbn");
        if (Strings.isNullOrEmpty(isbn)) {
            // Probably article
            entry.setType(StandardEntryType.Article);
            nametype = StandardField.JOURNAL;
        } else {
            // Probably book chapter or from proceeding, go for book chapter
            entry.setType(StandardEntryType.InCollection);
            nametype = StandardField.BOOKTITLE;
            entry.setField(StandardField.ISBN, isbn);
        }

        // Authors
        if (springerJsonEntry.has("creators")) {
            JSONArray authors = springerJsonEntry.getJSONArray("creators");
            List<String> authorList = new ArrayList<>();
            for (int i = 0; i < authors.length(); i++) {
                if (authors.getJSONObject(i).has("creator")) {
                    authorList.add(authors.getJSONObject(i).getString("creator"));
                } else {
                    LOGGER.info("Empty author name.");
                }
            }
            entry.setField(StandardField.AUTHOR, String.join(" and ", authorList));
        } else {
            LOGGER.info("No author found.");
        }

        // Direct accessible fields
        for (Field field : singleFieldStrings) {
            if (springerJsonEntry.has(field.getName())) {
                String text = springerJsonEntry.getString(field.getName());
                if (!text.isEmpty()) {
                    entry.setField(field, text);
                }
            }
        }

        // Page numbers
        if (springerJsonEntry.has("startingPage") && !springerJsonEntry.getString("startingPage").isEmpty()) {
            if (springerJsonEntry.has("endingPage") && !springerJsonEntry.getString("endingPage").isEmpty()) {
                entry.setField(StandardField.PAGES,
                        springerJsonEntry.getString("startingPage") + "--" + springerJsonEntry.getString("endingPage"));
            } else {
                entry.setField(StandardField.PAGES, springerJsonEntry.getString("startingPage"));
            }
        }

        // Journal
        if (springerJsonEntry.has("publicationName")) {
            entry.setField(nametype, springerJsonEntry.getString("publicationName"));
        }

        // Online file
        if (springerJsonEntry.has("url")) {
            JSONArray urls = springerJsonEntry.optJSONArray("url");
            if (urls == null) {
                entry.setField(StandardField.URL, springerJsonEntry.optString("url"));
            } else {
                urls.forEach(data -> {
                    JSONObject url = (JSONObject) data;
                    if ("pdf".equalsIgnoreCase(url.optString("format"))) {
                        try {
                            entry.addFile(new LinkedFile(URLUtil.create(url.optString("value")), "PDF"));
                        } catch (MalformedURLException e) {
                            LOGGER.info("Malformed URL: {}", url.optString("value"));
                        }
                    }
                });
            }
        }

        // Date
        if (springerJsonEntry.has("publicationDate")) {
            String date = springerJsonEntry.getString("publicationDate");
            entry.setField(StandardField.DATE, date); // For biblatex
            String[] dateparts = date.split("-");
            entry.setField(StandardField.YEAR, dateparts[0]);
            Optional<Month> month = Month.getMonthByNumber(Integer.parseInt(dateparts[1]));
            month.ifPresent(entry::setMonth);
        }

        // Clean up abstract (often starting with Abstract)
        entry.getField(StandardField.ABSTRACT).ifPresent(abstractContents -> {
            if (abstractContents.startsWith("Abstract")) {
                entry.setField(StandardField.ABSTRACT, abstractContents.substring(8));
            }
        });

        return entry;
    }

    @Override
    public String getName() {
        return FETCHER_NAME;
    }

    @Override
    public Optional<HelpFile> getHelpPage() {
        return Optional.of(HelpFile.FETCHER_SPRINGER);
    }

    @Override
    public String getTestUrl() {
        return TEST_URL_WITHOUT_API_KEY;
    }

    /**
     * Gets the query URL
     *
     * @param luceneQuery the search query
     * @param pageNumber  the number of the page indexed from 0
     * @return URL
     */
    @Override
    public URL getURLForQuery(QueryNode luceneQuery, int pageNumber) throws URISyntaxException, MalformedURLException {
        URIBuilder uriBuilder = new URIBuilder(API_URL);
        uriBuilder.addParameter("q", new SpringerQueryTransformer().transformLuceneQuery(luceneQuery).orElse("")); // Search query
        importerPreferences.getApiKey(getName()).ifPresent(key -> uriBuilder.addParameter("api_key", key)); // API key
        uriBuilder.addParameter("s", String.valueOf(getPageSize() * pageNumber + 1)); // Start entry, starts indexing at 1
        uriBuilder.addParameter("p", String.valueOf(getPageSize())); // Page size
        return uriBuilder.build().toURL();
    }

    private String constructComplexQueryString(ComplexSearchQuery complexSearchQuery) {
        List<String> searchTerms = new ArrayList<>();
        complexSearchQuery.getAuthors().forEach(author -> searchTerms.add("name:" + author));
        complexSearchQuery.getTitlePhrases().forEach(title -> searchTerms.add("title:" + title));
        complexSearchQuery.getJournal().ifPresent(journal -> searchTerms.add("journal:" + journal));
        // Since Springer API does not support year range search, we ignore formYear and toYear and use "singleYear" only
        complexSearchQuery.getSingleYear().ifPresent(year -> searchTerms.add("date:" + year + "*"));
        searchTerms.addAll(complexSearchQuery.getDefaultFieldPhrases());
        return String.join(" AND ", searchTerms);
    }

    @Override
    public Parser getParser() {
        return inputStream -> {
            String response = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining(OS.NEWLINE));
            JSONObject jsonObject = new JSONObject(response);

            List<BibEntry> entries = new ArrayList<>();
            if (jsonObject.has("records")) {
                JSONArray results = jsonObject.getJSONArray("records");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject jsonEntry = results.getJSONObject(i);
                    BibEntry entry = parseSpringerJSONtoBibtex(jsonEntry);
                    entries.add(entry);
                }
            }

            return entries;
        };
    }
}

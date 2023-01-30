//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.couchdb.nouveau.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;

import org.apache.couchdb.nouveau.api.After;
import org.apache.couchdb.nouveau.api.DocumentDeleteRequest;
import org.apache.couchdb.nouveau.api.DocumentUpdateRequest;
import org.apache.couchdb.nouveau.api.DoubleRange;
import org.apache.couchdb.nouveau.api.SearchHit;
import org.apache.couchdb.nouveau.api.SearchRequest;
import org.apache.couchdb.nouveau.api.SearchResults;
import org.apache.couchdb.nouveau.api.document.DoubleDocValuesField;
import org.apache.couchdb.nouveau.api.document.DoublePoint;
import org.apache.couchdb.nouveau.api.document.Field;
import org.apache.couchdb.nouveau.api.document.SortedDocValuesField;
import org.apache.couchdb.nouveau.api.document.SortedSetDocValuesField;
import org.apache.couchdb.nouveau.api.document.StoredDoubleField;
import org.apache.couchdb.nouveau.api.document.StoredStringField;
import org.apache.couchdb.nouveau.api.document.StringField;
import org.apache.couchdb.nouveau.api.document.TextField;
import org.apache.couchdb.nouveau.lucene9.lucene.analysis.Analyzer;
import org.apache.couchdb.nouveau.lucene9.lucene.document.Document;
import org.apache.couchdb.nouveau.lucene9.lucene.document.Field.Store;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.FacetResult;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.Facets;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.FacetsCollector;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.FacetsCollectorManager;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.LabelAndValue;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.StringDocValuesReaderState;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.StringValueFacetCounts;
import org.apache.couchdb.nouveau.lucene9.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.couchdb.nouveau.lucene9.lucene.index.IndexWriter;
import org.apache.couchdb.nouveau.lucene9.lucene.index.IndexWriterConfig;
import org.apache.couchdb.nouveau.lucene9.lucene.index.IndexableField;
import org.apache.couchdb.nouveau.lucene9.lucene.index.Term;
import org.apache.couchdb.nouveau.lucene9.lucene.misc.store.DirectIODirectory;
import org.apache.couchdb.nouveau.lucene9.lucene.search.CollectorManager;
import org.apache.couchdb.nouveau.lucene9.lucene.search.FieldDoc;
import org.apache.couchdb.nouveau.lucene9.lucene.search.IndexSearcher;
import org.apache.couchdb.nouveau.lucene9.lucene.search.MultiCollectorManager;
import org.apache.couchdb.nouveau.lucene9.lucene.search.Query;
import org.apache.couchdb.nouveau.lucene9.lucene.search.ScoreDoc;
import org.apache.couchdb.nouveau.lucene9.lucene.search.SearcherFactory;
import org.apache.couchdb.nouveau.lucene9.lucene.search.SearcherManager;
import org.apache.couchdb.nouveau.lucene9.lucene.search.Sort;
import org.apache.couchdb.nouveau.lucene9.lucene.search.SortField;
import org.apache.couchdb.nouveau.lucene9.lucene.search.TermQuery;
import org.apache.couchdb.nouveau.lucene9.lucene.search.TopDocs;
import org.apache.couchdb.nouveau.lucene9.lucene.search.TopFieldCollector;
import org.apache.couchdb.nouveau.lucene9.lucene.store.Directory;
import org.apache.couchdb.nouveau.lucene9.lucene.store.FSDirectory;
import org.apache.couchdb.nouveau.lucene9.lucene.util.BytesRef;

class LuceneIndex extends Index {

    private static final Sort DEFAULT_SORT = new Sort(SortField.FIELD_SCORE,
            new SortField("_id", SortField.Type.STRING));
    private static final Pattern SORT_FIELD_RE = Pattern.compile("^([-+])?([\\.\\w]+)(?:<(\\w+)>)?$");

    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    static Index open(final Path path, final Analyzer analyzer, final SearcherFactory searcherFactory)
            throws IOException {
        final Directory dir = new DirectIODirectory(FSDirectory.open(path));
        final IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setUseCompoundFile(false);
        final IndexWriter writer = new IndexWriter(dir, config);
        final long updateSeq = getUpdateSeq(writer);
        final SearcherManager searcherManager = new SearcherManager(writer, searcherFactory);
        return new LuceneIndex(analyzer, writer, updateSeq, searcherManager);
    }

    private LuceneIndex(final Analyzer analyzer, final IndexWriter writer, final long updateSeq,
            final SearcherManager searcherManager) {
        super(updateSeq);
        this.analyzer = analyzer;
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    @Override
    public int doNumDocs() throws IOException {
        return writer.getDocStats().numDocs;
    }

    @Override
    public void doUpdate(final String docId, final DocumentUpdateRequest request) throws IOException {
        final Term docIdTerm = docIdTerm(docId);
        final Document doc = toDocument(docId, request);
        writer.updateDocument(docIdTerm, doc);
    }

    @Override
    public void doDelete(final String docId, final DocumentDeleteRequest request) throws IOException {
        final Query query = docIdQuery(docId);
        writer.deleteDocuments(query);
    }

    @Override
    public boolean doCommit(final long updateSeq) throws IOException {
        writer.setLiveCommitData(Collections.singletonMap("update_seq", Long.toString(updateSeq)).entrySet());
        return writer.commit() != -1;
    }

    @Override
    public void doClose() throws IOException {
        writer.close();
    }

    @Override
    public boolean isOpen() {
        return writer.isOpen();
    }

    @Override
    public SearchResults doSearch(final SearchRequest request) throws IOException, QueryParserException {
        final Query query = newQueryParser().parse(request);

        // Construct CollectorManagers.
        final MultiCollectorManager cm;
        final CollectorManager<?, ? extends TopDocs> hits = hitCollector(request);

        searcherManager.maybeRefreshBlocking();

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            if (request.hasCounts() || request.hasRanges()) {
                cm = new MultiCollectorManager(hits, new FacetsCollectorManager());
            } else {
                cm = new MultiCollectorManager(hits);
            }
            final Object[] reduces = searcher.search(query, cm);
            return toSearchResults(request, searcher, reduces);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), e, Status.BAD_REQUEST);
        } finally {
            searcherManager.release(searcher);
        }
    }

    private CollectorManager<?, ? extends TopDocs> hitCollector(final SearchRequest searchRequest) {
        final Sort sort = toSort(searchRequest);

        final After after = searchRequest.getAfter();
        final FieldDoc fieldDoc;
        if (after != null) {
            fieldDoc = toFieldDoc(after);
            if (getLastSortField(sort).getReverse()) {
                fieldDoc.doc = 0;
            } else {
                fieldDoc.doc = Integer.MAX_VALUE;
            }
        } else {
            fieldDoc = null;
        }

        return TopFieldCollector.createSharedManager(
                sort,
                searchRequest.getLimit(),
                fieldDoc,
                1000);
    }

    private SortField getLastSortField(final Sort sort) {
        final SortField[] sortFields = sort.getSort();
        return sortFields[sortFields.length - 1];
    }

    private SearchResults toSearchResults(final SearchRequest searchRequest, final IndexSearcher searcher,
            final Object[] reduces) throws IOException {
        final SearchResults result = new SearchResults();
        collectHits(searcher, (TopDocs) reduces[0], result);
        if (reduces.length == 2) {
            collectFacets(searchRequest, searcher, (FacetsCollector) reduces[1], result);
        }
        return result;
    }

    private void collectHits(final IndexSearcher searcher, final TopDocs topDocs, final SearchResults searchResults)
            throws IOException {
        final List<SearchHit> hits = new ArrayList<SearchHit>(topDocs.scoreDocs.length);

        for (final ScoreDoc scoreDoc : topDocs.scoreDocs) {
            final Document doc = searcher.doc(scoreDoc.doc);

            final List<Field> fields = new ArrayList<Field>(doc.getFields().size());
            for (IndexableField field : doc.getFields()) {
                if (!field.name().equals("_id")) {
                    fields.add(toField(field));
                }
            }

            final After after = toAfter(((FieldDoc)scoreDoc));
            hits.add(new SearchHit(doc.get("_id"), after, fields));
        }

        searchResults.setTotalHits(topDocs.totalHits.value);
        searchResults.setTotalHitsRelation(topDocs.totalHits.relation.name());
        searchResults.setHits(hits);
    }

    private void collectFacets(final SearchRequest searchRequest, final IndexSearcher searcher,
            final FacetsCollector fc, final SearchResults searchResults) throws IOException {
        if (searchRequest.hasCounts()) {
            final Map<String, Map<String, Number>> countsMap = new HashMap<String, Map<String, Number>>(
                    searchRequest.getCounts().size());
            for (final String field : searchRequest.getCounts()) {
                final StringDocValuesReaderState state = new StringDocValuesReaderState(searcher.getIndexReader(),
                        field);
                final StringValueFacetCounts counts = new StringValueFacetCounts(state, fc);
                countsMap.put(field, collectFacets(counts, searchRequest.getTopN(), field));
            }
            searchResults.setCounts(countsMap);
        }

        if (searchRequest.hasRanges()) {
            final Map<String, Map<String, Number>> rangesMap = new HashMap<String, Map<String, Number>>(
                    searchRequest.getRanges().size());
            for (final Entry<String, List<DoubleRange>> entry : searchRequest.getRanges().entrySet()) {
                final DoubleRangeFacetCounts counts = toDoubleRangeFacetCounts(fc, entry.getKey(), entry.getValue());
                rangesMap.put(entry.getKey(), collectFacets(counts, searchRequest.getTopN(), entry.getKey()));
            }
            searchResults.setRanges(rangesMap);
        }
    }

    private DoubleRangeFacetCounts toDoubleRangeFacetCounts(final FacetsCollector fc, final String field, final List<DoubleRange> ranges) throws IOException {
        final org.apache.couchdb.nouveau.lucene9.lucene.facet.range.DoubleRange[] luceneRanges = new org.apache.couchdb.nouveau.lucene9.lucene.facet.range.DoubleRange[ranges.size()];
        for (int i = 0; i < luceneRanges.length; i++) {
            final DoubleRange range = ranges.get(i);
            luceneRanges[i] = new org.apache.couchdb.nouveau.lucene9.lucene.facet.range.DoubleRange(
                range.getLabel(), range.getMin(), range.isMinInclusive(), range.getMax(), range.isMaxInclusive());
        }
        return new DoubleRangeFacetCounts(field, fc, luceneRanges);
    }

    private Map<String, Number> collectFacets(final Facets facets, final int topN, final String dim)
            throws IOException {
        final FacetResult topChildren = facets.getTopChildren(topN, dim);
        final Map<String, Number> result = new HashMap<String, Number>(topChildren.childCount);
        for (final LabelAndValue lv : topChildren.labelValues) {
            result.put(lv.label, lv.value);
        }
        return result;
    }

    // Ensure _id is final sort field so we can paginate.
    private Sort toSort(final SearchRequest searchRequest) {
        if (!searchRequest.hasSort()) {
            return DEFAULT_SORT;
        }

        final List<String> sort = new ArrayList<String>(searchRequest.getSort());
        final String last = sort.get(sort.size() - 1);
        // Append _id field if not already present.
        switch (last) {
            case "-_id<string>":
            case "_id<string>":
                break;
            default:
                sort.add("_id<string>");
        }
        return convertSort(sort);
    }

    private Sort convertSort(final List<String> sort) {
        final SortField[] fields = new SortField[sort.size()];
        for (int i = 0; i < sort.size(); i++) {
            fields[i] = convertSortField(sort.get(i));
        }
        return new Sort(fields);
    }

    private SortField convertSortField(final String sortString) {
        final Matcher m = SORT_FIELD_RE.matcher(sortString);
        if (!m.matches()) {
            throw new WebApplicationException(
                    sortString + " is not a valid sort parameter", Status.BAD_REQUEST);
        }
        final boolean reverse = "-".equals(m.group(1));
        SortField.Type type = SortField.Type.DOUBLE;
        if ("string".equals(m.group(3))) {
            type = SortField.Type.STRING;
        }
        return new SortField(m.group(2), type, reverse);
    }

    private static Document toDocument(final String docId, final DocumentUpdateRequest request) throws IOException {
        final Document result = new Document();

        // id
        result.add(new org.apache.couchdb.nouveau.lucene9.lucene.document.StringField("_id", docId, Store.YES));
        result.add(new org.apache.couchdb.nouveau.lucene9.lucene.document.SortedDocValuesField("_id", new BytesRef(docId)));

        // partition (optional)
        if (request.hasPartition()) {
            result.add(new org.apache.couchdb.nouveau.lucene9.lucene.document.StringField("_partition", request.getPartition(), Store.NO));
        }

        for (Field field : request.getFields()) {
            // Underscore-prefix is reserved.
            if (field.getName().startsWith("_")) {
                continue;
            }
            result.add(toIndexableField(field));
        }

        return result;
    }

    private static IndexableField toIndexableField(final Field field) {
        if (field instanceof DoubleDocValuesField) {
            final DoubleDocValuesField f = (DoubleDocValuesField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.DoubleDocValuesField(f.getName(), f.getValue());
        }
        if (field instanceof DoublePoint) {
            final DoublePoint f = (DoublePoint) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.DoublePoint(f.getName(), f.getValue());
        }
        if (field instanceof SortedDocValuesField) {
            final SortedDocValuesField f = (SortedDocValuesField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.SortedDocValuesField(f.getName(), new BytesRef(f.getValue()));
        }
        if (field instanceof SortedSetDocValuesField) {
            final SortedSetDocValuesField f = (SortedSetDocValuesField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.SortedSetDocValuesField(f.getName(), new BytesRef(f.getValue()));
        }
        if (field instanceof StoredDoubleField) {
            final StoredDoubleField f = (StoredDoubleField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.StoredField(f.getName(), f.getValue());
        }
        if (field instanceof StoredStringField) {
            final StoredStringField f = (StoredStringField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.StoredField(f.getName(), f.getValue());
        }
        if (field instanceof StringField) {
            final StringField f = (StringField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.StringField(f.getName(), f.getValue(),
                f.isStored() ? Store.YES : Store.NO);
        }
        if (field instanceof TextField) {
            final StringField f = (StringField) field;
            return new org.apache.couchdb.nouveau.lucene9.lucene.document.TextField(f.getName(), f.getValue(),
                f.isStored() ? Store.YES : Store.NO);
        }
        throw new WebApplicationException(field + " is not valid", Status.BAD_REQUEST);
    }

    private static Field toField(final IndexableField field) {
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.DoubleDocValuesField) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.DoubleDocValuesField f = (org.apache.couchdb.nouveau.lucene9.lucene.document.DoubleDocValuesField) field;
            return new DoubleDocValuesField(f.name(), (double) f.numericValue());
        }
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.DoublePoint) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.DoublePoint f = (org.apache.couchdb.nouveau.lucene9.lucene.document.DoublePoint) field;
            return new DoublePoint(f.name(), (double) f.numericValue());
        }
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.SortedDocValuesField) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.SortedDocValuesField f = (org.apache.couchdb.nouveau.lucene9.lucene.document.SortedDocValuesField) field;
            return new SortedDocValuesField(f.name(), toBytes(f.binaryValue()));
        }
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.SortedSetDocValuesField) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.SortedSetDocValuesField f = (org.apache.couchdb.nouveau.lucene9.lucene.document.SortedSetDocValuesField) field;
            return new SortedSetDocValuesField(f.name(), toBytes(f.binaryValue()));
        }
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.StoredField) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.StoredField f = (org.apache.couchdb.nouveau.lucene9.lucene.document.StoredField) field;
            if (f.stringValue() != null) {
                return new StoredStringField(f.name(), f.stringValue());
            }
            if (f.numericValue() != null && f.numericValue() instanceof Double) {
                return new StoredDoubleField(f.name(), (Double) f.numericValue());
            }
        }
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.StringField) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.StringField f = (org.apache.couchdb.nouveau.lucene9.lucene.document.StringField) field;
            return new StringField(f.name(), f.stringValue(), f.fieldType().stored());
        }
        if (field instanceof org.apache.couchdb.nouveau.lucene9.lucene.document.TextField) {
            final org.apache.couchdb.nouveau.lucene9.lucene.document.StringField f = (org.apache.couchdb.nouveau.lucene9.lucene.document.StringField) field;
            return new TextField(f.name(), f.stringValue(), f.fieldType().stored());
        }
        throw new WebApplicationException(field + " is not valid", Status.BAD_REQUEST);
    }

    private FieldDoc toFieldDoc(final After after) {
        final Object[] fields = Arrays.copyOf(after.getFields(), after.getFields().length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] instanceof byte[]) {
                fields[i] = new BytesRef((byte[])fields[i]);
            }
        }
        return new FieldDoc(0, Float.NaN, fields);
    }

    private After toAfter(final FieldDoc fieldDoc) {
        final Object[] fields = Arrays.copyOf(fieldDoc.fields, fieldDoc.fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] instanceof BytesRef) {
                fields[i] = toBytes((BytesRef)fields[i]);
            }
        }
        return new After(fields);
    }

    private static byte[] toBytes(final BytesRef bytesRef) {
        return Arrays.copyOfRange(bytesRef.bytes, bytesRef.offset, bytesRef.offset + bytesRef.length);
    }

    private static Query docIdQuery(final String docId) {
        return new TermQuery(docIdTerm(docId));
    }

    private static Term docIdTerm(final String docId) {
        return new Term("_id", docId);
    }

    private static long getUpdateSeq(final IndexWriter writer) throws IOException {
        final Iterable<Map.Entry<String, String>> commitData = writer.getLiveCommitData();
        if (commitData == null) {
            return 0L;
        }
        for (Map.Entry<String, String> entry : commitData) {
            if (entry.getKey().equals("update_seq")) {
                return Long.parseLong(entry.getValue());
            }
        }
        return 0L;
    }

    public QueryParser newQueryParser() {
        return new NouveauQueryParser("default", analyzer);
    }

}

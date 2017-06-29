/**
 *  ElasticsearchClient
 *  Copyright 18.02.2016 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.io.index;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jetty.util.log.Log;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsAction;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsNodes;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsRequest;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsRequestBuilder;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsResponse;
import org.elasticsearch.action.admin.cluster.tasks.PendingClusterTasksResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.search.MatchQuery.ZeroTermsQuery;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

public class ElasticsearchClient {

    private static long throttling_time_threshold = 2000L; // update time high limit
    private static long throttling_ops_threshold = 1000L; // messages per second low limit
    private static double throttling_factor = 1.0d; // factor applied on update duration if both thresholds are passed
    
    public final static BulkWriteResult EMPTY_BULK_RESULT = new BulkWriteResult();
    
    private Client elasticsearchClient;

    /**
     * create a elasticsearch transport client (remote elasticsearch)
     * @param addresses an array of host:port addresses
     * @param clusterName
     */
    public ElasticsearchClient(final String[] addresses, final String clusterName) {
        // create default settings and add cluster name
        Settings.Builder settings = Settings.builder()
                .put("cluster.routing.allocation.enable", "all")
                .put("cluster.routing.allocation.allow_rebalance", "always");
        if (clusterName != null) settings.put("cluster.name", clusterName);
        
        // create a client
        TransportClient tc = new PreBuiltTransportClient(settings.build());

        for (String address: addresses) {
            String a = address.trim();
            int p = a.indexOf(':');
            if (p >= 0) try {
                InetAddress i = InetAddress.getByName(a.substring(0, p));
                int port = Integer.parseInt(a.substring(p + 1));
                tc.addTransportAddress(new InetSocketTransportAddress(i, port));
            } catch (UnknownHostException e) {
                Log.getLog().warn(e);
            }
        }
        this.elasticsearchClient = tc;
    }

    public ClusterStatsNodes getClusterStatsNodes() {
        ClusterStatsRequest clusterStatsRequest =
            new ClusterStatsRequestBuilder(elasticsearchClient.admin().cluster(), ClusterStatsAction.INSTANCE).request();
        ClusterStatsResponse clusterStatsResponse =
            elasticsearchClient.admin().cluster().clusterStats(clusterStatsRequest).actionGet();
        ClusterStatsNodes clusterStatsNodes = clusterStatsResponse.getNodesStats();
        return clusterStatsNodes;
    }
    
    private boolean clusterReadyCache = false;
    public boolean clusterReady() {
        if (clusterReadyCache) return true;
        ClusterHealthResponse chr = elasticsearchClient.admin().cluster().prepareHealth().get();
        clusterReadyCache = chr.getStatus() != ClusterHealthStatus.RED;
        return clusterReadyCache;
    }

    public boolean wait_ready(long maxtimemillis, ClusterHealthStatus status) {
        // wait for yellow status
        long start = System.currentTimeMillis();
        boolean is_ready;
        do {
            // wait for yellow status
            ClusterHealthResponse health = elasticsearchClient.admin().cluster().prepareHealth().setWaitForStatus(status).execute().actionGet();
            is_ready = !health.isTimedOut();
            if (!is_ready && System.currentTimeMillis() - start > maxtimemillis) return false; 
        } while (!is_ready);
        return is_ready;
    }
    
    /**
     * create a new index. This method must be called to ensure that an elasticsearch index is available and can be used.
     * @param indexName
     * @param shards
     * @param replicas
     * @throws NoNodeAvailableException in case that no elasticsearch server can be contacted.
     */
    public void createIndexIfNotExists(String indexName, final int shards, final int replicas) throws NoNodeAvailableException {
        // create an index if not existent
        if (!this.elasticsearchClient.admin().indices().prepareExists(indexName).execute().actionGet().isExists()) {
            Settings.Builder settings = Settings.builder()
                    .put("number_of_shards", shards)
                    .put("number_of_replicas", replicas);
            this.elasticsearchClient.admin().indices().prepareCreate(indexName)
                .setSettings(settings)
                .setUpdateAllTypes(true)
                .execute().actionGet();
        } else {
            //LOGGER.debug("Index with name {} already exists", indexName);
        }
    }

    public void setMapping(String indexName, XContentBuilder mapping) {
        try {
            this.elasticsearchClient.admin().indices().preparePutMapping(indexName)
                .setSource(mapping)
                .setUpdateAllTypes(true)
                .setType("_default_").execute().actionGet();
        } catch (Throwable e) {
            Log.getLog().warn(e);
        };
    }

    public void setMapping(String indexName, Map<String, Object> mapping) {
        try {
            this.elasticsearchClient.admin().indices().preparePutMapping(indexName)
                .setSource(mapping)
                .setUpdateAllTypes(true)
                .setType("_default_").execute().actionGet();
        } catch (Throwable e) {
            Log.getLog().warn(e);
        };
    }

    public void setMapping(String indexName, String mapping) {
        try {
            this.elasticsearchClient.admin().indices().preparePutMapping(indexName)
                .setSource(mapping)
                .setUpdateAllTypes(true)
                .setType("_default_").execute().actionGet();
        } catch (Throwable e) {
            Log.getLog().warn(e);
        };
    }

    public void setMapping(String indexName, File json) {
        try {
            this.elasticsearchClient.admin().indices().preparePutMapping(indexName)
                .setSource(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8))
                .setUpdateAllTypes(true)
                .setType("_default_")
                .execute()
                .actionGet();
        } catch (Throwable e) {
            Log.getLog().warn(e);
        };
    }

    public String pendingClusterTasks() {
        PendingClusterTasksResponse r = this.elasticsearchClient.admin().cluster().preparePendingClusterTasks().get();
        return r.toString();
    }
    
    public String clusterStats() {
        ClusterStatsResponse r = this.elasticsearchClient.admin().cluster().prepareClusterStats().get();
        return r.toString();
    }

    public Map<String, String> nodeSettings() {
        return this.elasticsearchClient.settings().getAsMap();
    }
    
    /**
     * Close the connection to the remote elasticsearch client. This should only be called when the application is
     * terminated.
     * Please avoid to open and close the ElasticsearchClient for the same cluster and index more than once.
     * To avoid that this method is called more than once, the elasticsearch_client object is set to null
     * as soon this was called the first time. This is needed because the finalize method calls this
     * method as well.
     */
    public void close() {
        if (this.elasticsearchClient != null) {
            this.elasticsearchClient.close();
            this.elasticsearchClient = null;
        }
    }


    /**
     * A finalize method is added to ensure that close() is always called.
     */
    public void finalize() {
        this.close(); // will not cause harm if this is the second call to close()
    }


    /**
     * Retrieve a statistic object from the connected elasticsearch cluster
     * 
     * @return cluster stats from connected cluster
     */
    public ClusterStatsNodes getStats() {
        final ClusterStatsRequest clusterStatsRequest =
            new ClusterStatsRequestBuilder(elasticsearchClient.admin().cluster(), ClusterStatsAction.INSTANCE)
                .request();
        final ClusterStatsResponse clusterStatsResponse =
            elasticsearchClient.admin().cluster().clusterStats(clusterStatsRequest).actionGet();
        final ClusterStatsNodes clusterStatsNodes = clusterStatsResponse.getNodesStats();
        return clusterStatsNodes;
    }


    /**
     * Get the number of documents in the search index
     * 
     * @return the count of all documents in the index
     */
    public long count(String indexName) {
        return count(QueryBuilders.constantScoreQuery(QueryBuilders.matchAllQuery()), indexName);
    }


    /**
     * Get the number of documents in the search index for a given search query
     * 
     * @param q
     *            the query
     * @return the count of all documents in the index which matches with the query
     */
    public long count(final QueryBuilder q, final String indexName) {
        SearchResponse response =
            elasticsearchClient.prepareSearch(indexName).setQuery(q).setSize(0).execute().actionGet();
        return response.getHits().getTotalHits();
    }

    public long count(final String index, final String histogram_timefield, final long millis) {
        try {
            SearchResponse response = elasticsearchClient.prepareSearch(index)
                .setSize(0)
                .setQuery(millis <= 0 ? QueryBuilders.constantScoreQuery(QueryBuilders.matchAllQuery()) : QueryBuilders.rangeQuery(histogram_timefield).from(new Date(System.currentTimeMillis() - millis)))
                .execute()
                .actionGet();
            return response.getHits().getTotalHits();
        } catch (Throwable e) {
            Log.getLog().warn(e);
            return 0;
        }
    }
    
    public long countLocal(final String index, final String provider_hash) {
        try {
            SearchResponse response = elasticsearchClient.prepareSearch(index)
                .setSize(0)
                .setQuery(QueryBuilders.matchQuery("provider_hash", provider_hash))
                .execute()
                .actionGet();
            return response.getHits().getTotalHits();
        } catch (Throwable e) {
            Log.getLog().warn(e);
            return 0;
        }
    }

    /**
     * Get the document for a given id. If you don't know the typeName of the index, then it is not recommended
     * to use this method. You can set typeName to null and get the correct answer, but you still need the information
     * in which type the document was found if you want to call this API with the type afterwards. In such a case,
     * use the method getType() which returns null if the document does not exist and the type name if the document exist.
     * DO NOT USE THIS METHOD if you call getType anyway. I.e. replace a code like
     * if (exist(id()) {
     *   String type = getType(id);
     *   ...
     * }
     * with
     * String type = getType(id);
     * if (type != null) {
     *   ...
     * }
     * 
     * @param indexName
     *            the name of the index
     * @param typeName
     *            the type name, can be set to NULL for all types (see also: getType())
     * @param id
     *            the unique identifier of a document
     * @return the document, if it exists or null otherwise;
     */
    public boolean exist(String indexName, String typeName, final String id) {
        GetResponse getResponse = elasticsearchClient
                .prepareGet(indexName, typeName, id)
                .setOperationThreaded(false)
                .execute()
                .actionGet();
        return getResponse.isExists();
    }    

    public Set<String> existBulk(String indexName, String typeName, final Collection<String> ids) {
        if (ids == null || ids.size() == 0) return new HashSet<>();
        MultiGetResponse multiGetItemResponses = elasticsearchClient.prepareMultiGet()
                .add(indexName, typeName, ids)
                .get();
        Set<String> er = new HashSet<>();
        for (MultiGetItemResponse itemResponse : multiGetItemResponses) { 
            GetResponse response = itemResponse.getResponse();
            if (response.isExists()) {                      
                er.add(response.getId());
            }
        }
        return er;
    }    
    
    /**
     * Get the type name of a document or null if the document does not exist.
     * This is a replacement of the exist() method which does exactly the same as exist()
     * but is able to return the type name in case that exist is successful.
     * Please read the comment to exist() for details.
     * @param indexName
     *            the name of the index
     * @param id
     *            the unique identifier of a document
     * @return the type name of the document if it exists, null otherwise
     */
    public String getType(String indexName, final String id) {
        GetResponse getResponse = elasticsearchClient.prepareGet(indexName, null, id).execute().actionGet();
        return getResponse.isExists() ? getResponse.getType() : null;
    }

    /**
     * Delete a document for a given id.
     * ATTENTION: deleted documents cannot be re-inserted again if version number
     * checking is used and the new document does not comply to the version number
     * rule. The information which document was deleted persists for one minute and
     * then inserting documents with the same version number as before is possible.
     * To modify this behavior, change the configuration setting index.gc_deletes
     * 
     * @param id
     *            the unique identifier of a document
     * @return true if the document existed and was deleted, false otherwise
     */
    public boolean delete(String indexName, String typeName, final String id) {
        DeleteResponse response = elasticsearchClient.prepareDelete(indexName, typeName, id).get();
        return response.getResult() == DocWriteResponse.Result.DELETED;
    }

    /**
     * Delete a list of documents for a given set of ids
     * ATTENTION: read about the time-out of version number checking in the method above.
     * 
     * @param ids
     *            a map from the unique identifier of a document to the document type
     * @return the number of deleted documents
     */
    public int deleteBulk(String indexName, Map<String, String> ids) {
        // bulk-delete the ids
        if (ids == null || ids.size() == 0) return 0;
        BulkRequestBuilder bulkRequest = elasticsearchClient.prepareBulk();
        for (Map.Entry<String, String> id : ids.entrySet()) {
            bulkRequest.add(new DeleteRequest().id(id.getKey()).index(indexName).type(id.getValue()));
        }
        bulkRequest.execute().actionGet();
        return ids.size();
    }
    
    /**
     * Delete documents using a query. Check what would be deleted first with a normal search query!
     * Elasticsearch once provided a native prepareDeleteByQuery method, but this was removed
     * in later versions. Instead, there is a plugin which iterates over search results,
     * see https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugins-delete-by-query.html
     * We simulate the same behaviour here without the need of that plugin.
     * 
     * @param q
     * @return delete document count
     */
    public int deleteByQuery(String indexName, final QueryBuilder q) {
        Map<String, String> ids = new TreeMap<>();
        SearchResponse response = elasticsearchClient.prepareSearch(indexName).setSearchType(SearchType.QUERY_THEN_FETCH)
            .setScroll(new TimeValue(60000)).setQuery(q).setSize(100).execute().actionGet();
        while (true) {
            // accumulate the ids here, don't delete them right now to prevent an interference of the delete with the
            // scroll
            for (SearchHit hit : response.getHits().getHits()) {
                ids.put(hit.getId(), hit.getType());
            }
            response = elasticsearchClient.prepareSearchScroll(response.getScrollId()).setScroll(new TimeValue(600000))
                .execute().actionGet();
            // termination
            if (response.getHits().getHits().length == 0)
                break;
        }
        return deleteBulk(indexName, ids);
    }

    /**
     * Read a document from the search index for a given id.
     * This is the cheapest document retrieval from the '_source' field because
     * elasticsearch does not do any json transformation or parsing. We
     * get simply the text from the '_source' field. This might be useful to
     * make a dump from the index content.
     * 
     * @param id
     *            the unique identifier of a document
     * @return the document as source text
     */
    public byte[] readSource(String indexName, final String id) {
        GetResponse response = elasticsearchClient.prepareGet(indexName, null, id).execute().actionGet();
        return response.getSourceAsBytes();
    }

    /**
     * Read a json document from the search index for a given id.
     * Elasticsearch reads the '_source' field and parses the content as json.
     * 
     * @param id
     *            the unique identifier of a document
     * @return the document as json, matched on a Map<String, Object> object instance
     */
    public Map<String, Object> readMap(String indexName, final String id) {
        GetResponse response = elasticsearchClient.prepareGet(indexName, null, id).execute().actionGet();
        Map<String, Object> map = getMap(response);
        return map;
    }
    
    protected static Map<String, Object> getMap(GetResponse response) {
        Map<String, Object> map = null;
        if (response.isExists() && (map = response.getSourceAsMap()) != null) {
            map.put("$type", response.getType());
        }
        return map;
    }
    
    /**
     * Write a json document into the search index.
     * Writing using a XContentBuilder is the most efficient way to add content to elasticsearch
     * 
     * @param jsonMap
     *            the json document to be indexed in elasticsearch
     * @param id
     *            the unique identifier of a document
     * @param indexName
     *            the name of the index
     * @param typeName
     *            the type of the index
     */
    public IndexResponse writeSource(String indexName, XContentBuilder json, String id, String typeName, long version, VersionType versionType) {
        // put this to the index
        IndexResponse r = elasticsearchClient.prepareIndex(indexName, typeName, id).setSource(json)
            .setVersion(version).setVersionType(versionType).execute()
            .actionGet();
        // documentation about the versioning is available at
        // https://www.elastic.co/blog/elasticsearch-versioning-support
        // TODO: error handling
        return r;
    }

    /**
     * Write a json document into the search index. The id must be calculated by the calling environment.
     * This id should be unique for the json. The best way to calculate this id is, to use an existing
     * field from the jsonMap which contains a unique identifier for the jsonMap.
     * 
     * @param indexName
     *            the name of the index
     * @param jsonMap
     *            the json document to be indexed in elasticsearch
     * @param typeName
     *            the type of the index
     * @param id
     *            the unique identifier of a document
     * @return true if the document with given id did not exist before, false if it existed and was overwritten
     */
    public boolean writeMap(String indexName, final Map<String, Object> jsonMap, String typeName, String id) {
        long start = System.currentTimeMillis();
        // get the version number out of the json, if any is given
        Long version = (Long) jsonMap.remove("_version");
        // put this to the index
        IndexResponse r = elasticsearchClient.prepareIndex(indexName, typeName, id).setSource(jsonMap)
            .setVersion(version == null ? 1 : version.longValue())
            .setVersionType(version == null ? VersionType.FORCE : VersionType.EXTERNAL)
            .execute()
            .actionGet();
        if (version != null) jsonMap.put("_version", version); // to prevent side effects
        // documentation about the versioning is available at
        // https://www.elastic.co/blog/elasticsearch-versioning-support
        // TODO: error handling
        boolean created = r.status() == RestStatus.CREATED; // true means created, false means updated
        long duration = Math.max(1, System.currentTimeMillis() - start);
        long regulator = 0;
        /*
        if (duration > throttling_time_threshold) {
            regulator = (long) (throttling_factor * duration);
            try {Thread.sleep(regulator);} catch (InterruptedException e) {}
        }
        */
        Log.getLog().info("elastic write entry to index " + indexName + ": " + (created ? "created":"updated") + ", " + duration + " ms" + (regulator == 0 ? "" : ", throttled with " + regulator + " ms"));
        return created;
    }

    /**
     * bulk message write
     * @param jsonMapList
     *            a list of json documents to be indexed
     * @param indexName
     *            the name of the index
     * @param typeName
     *            the type of the index
     * @return a list with error messages.
     *            The key is the id of the document, the value is an error string.
     *            The method was only successful if this list is empty.
     *            This must be a list, because keys may appear several times.
     */
    public BulkWriteResult writeMapBulk(final String indexName, final List<BulkEntry> jsonMapList) {
        long start = System.currentTimeMillis();
        BulkRequestBuilder bulkRequest = elasticsearchClient.prepareBulk();
        for (BulkEntry be: jsonMapList) {
            if (be.id == null) continue;
            bulkRequest.add(
                    elasticsearchClient.prepareIndex(indexName, be.type, be.id).setSource(be.jsonMap)
                        .setVersion(be.version == null ? 1 : be.version.longValue())
                        .setVersionType(be.version == null ? VersionType.FORCE : VersionType.EXTERNAL));
        }
        BulkResponse bulkResponse = bulkRequest.get();
        BulkWriteResult result = new BulkWriteResult();
        for (BulkItemResponse r: bulkResponse.getItems()) {
            String id = r.getId();
            DocWriteResponse response = r.getResponse();
            if (response.getResult() == DocWriteResponse.Result.CREATED) result.created.add(id);
            String err = r.getFailureMessage();
            if (err != null) {
                result.errors.put(id, err);
            }
        }
        long duration = Math.max(1, System.currentTimeMillis() - start);
        long regulator = 0;
        int created = result.created.size();
        long ops = created * 1000 / duration;
        if (duration > throttling_time_threshold && ops < throttling_ops_threshold) {
            regulator = (long) (throttling_factor * duration);
            try {Thread.sleep(regulator);} catch (InterruptedException e) {}
        }
        Log.getLog().info("elastic write bulk to index " + indexName + ": " + jsonMapList.size() + " entries, " + result.created.size() + " created, " + result.errors.size() + " errors, " + duration + " ms" + (regulator == 0 ? "" : ", throttled with " + regulator + " ms") + ", " + ops + " objects/second");
        return result;
    }
    
    public static class BulkWriteResult {
        private Map<String, String> errors;
        private Set<String> created;
        public BulkWriteResult() {
            this.errors = new LinkedHashMap<>();
            this.created = new LinkedHashSet<>();
        }
        public Map<String, String> getErrors() {
            return this.errors;
        }
        public Set<String> getCreated() {
            return this.created;
        }
    }

    private final static DateTimeFormatter utcFormatter = ISODateTimeFormat.dateTime().withZoneUTC();
    
    public static class BulkEntry {
        private String id;
        private String type;
        private Long version;
        public Map<String, Object> jsonMap;
        
        /**
         * initialize entry for bulk writes
         * @param id the id of the entry
         * @param type the type name
         * @param timestamp_fieldname the name of the timestamp field, null for unused. If a name is given here, then this field is filled with the current time
         * @param version the version number >= 0 for external versioning or null for forced updates without versioning
         * @param jsonMap the payload object
         */
        public BulkEntry(final String id, final String type, final String timestamp_fieldname, final Long version, final Map<String, Object> jsonMap) {
            this.id = id;
            this.type = type;
            this.version = version;
            this.jsonMap = jsonMap;
            if (timestamp_fieldname != null && !this.jsonMap.containsKey(timestamp_fieldname)) this.jsonMap.put(timestamp_fieldname, utcFormatter.print(System.currentTimeMillis()));
        }
    }

    /**
     * Query with a string and boundaries.
     * The string is supposed to be something that the user types in without a technical syntax.
     * The mapping of the search terms into the index can be different according
     * to a search type. Please see
     * https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-match-query.html.
     * A better way to do this would be the usage of a cursor.
     * See the delete method to find out how cursors work.
     * 
     * @param q
     *            a search query string
     * @param operator
     *            either AND or OR, the default operator for the query tokens
     * @param offset
     *            the first document number, 0 is the first one
     * @param count
     *            the number of documents to be returned
     * @return a list of json objects, mapped as Map<String,Object> for each json
     */
    public List<Map<String, Object>> query(final String indexName, final String q, final Operator operator, final int offset, final int count) {
        assert count > 1; // for smaller amounts, use the next method
        SearchRequestBuilder request = elasticsearchClient.prepareSearch(indexName)
            // .addFields("_all")
            .setQuery(QueryBuilders.multiMatchQuery(q, "_all").operator(operator).zeroTermsQuery(ZeroTermsQuery.ALL)).setFrom(offset).setSize(count);
        SearchResponse response = request.execute().actionGet();
        SearchHit[] hits = response.getHits().getHits();
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (SearchHit hit : hits) {
            Map<String, Object> map = hit.getSource();
            result.add(map);
        }
        return result;
    }

    public Map<String, Object> query(final String indexName, final String field_name, String field_value) {
        if (field_value == null || field_value.length() == 0) return null;
        // prepare request
        BoolQueryBuilder query = QueryBuilders.boolQuery();
        query.filter(QueryBuilders.constantScoreQuery(QueryBuilders.termQuery(field_name, field_value)));

        SearchRequestBuilder request = elasticsearchClient.prepareSearch(indexName)
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setQuery(query)
                .setFrom(0)
                .setSize(1).setTerminateAfter(1);

        // get response
        SearchResponse response = request.execute().actionGet();

        // evaluate search result
        //long totalHitCount = response.getHits().getTotalHits();
        SearchHit[] hits = response.getHits().getHits();
        if (hits.length == 0) return null;
        assert hits.length == 1;
        Map<String, Object> map = hits[0].getSource();
        return map;
    }
    
    public Query query(final String indexName, final QueryBuilder queryBuilder, String order_field, int timezoneOffset, int resultCount, int aggregationLimit, String... aggregationFields) {
        return new Query(indexName,  queryBuilder, order_field, timezoneOffset, resultCount, aggregationLimit, aggregationFields);
    }
    
    public class Query {
        public List<Map<String, Object>> result;
        public int hitCount;
        public Map<String, List<Map.Entry<String, Long>>> aggregations;

        /**
         * Search the local message cache using a elasticsearch query.
         * @param q - the query, for aggregation this which should include a time frame in the form since:yyyy-MM-dd until:yyyy-MM-dd
         * @param order_field - the field to order the results, i.e. Timeline.Order.CREATED_AT
         * @param timezoneOffset - an offset in minutes that is applied on dates given in the query of the form since:date until:date
         * @param resultCount - the number of messages in the result; can be zero if only aggregations are wanted
         * @param dateHistogrammInterval - the date aggregation interval or null, if no aggregation wanted
         * @param aggregationLimit - the maximum count of facet entities, not search results
         * @param aggregationFields - names of the aggregation fields. If no aggregation is wanted, pass no (zero) field(s)
         */
        public Query(final String indexName, final QueryBuilder queryBuilder, String order_field, int timezoneOffset, int resultCount, int aggregationLimit, String... aggregationFields) {
            // prepare request
            SearchRequestBuilder request = elasticsearchClient.prepareSearch(indexName)
                    .setSearchType(SearchType.QUERY_THEN_FETCH)
                    .setQuery(queryBuilder)
                    .setFrom(0)
                    .setSize(resultCount);
            request.clearRescorers();
            if (resultCount > 0) {
                request.addSort(
                        SortBuilders.fieldSort(order_field)
                            .unmappedType(order_field)
                            .order(SortOrder.DESC)
                        );
            }
            for (String field: aggregationFields) {
                request.addAggregation(AggregationBuilders.terms(field).field(field).minDocCount(1).size(aggregationLimit));
            }
            // get response
            SearchResponse response = request.execute().actionGet();
            hitCount = (int) response.getHits().getTotalHits();
                    
            // evaluate search result
            //long totalHitCount = response.getHits().getTotalHits();
            SearchHit[] hits = response.getHits().getHits();
            this.result = new ArrayList<Map<String, Object>>(hitCount);
            for (SearchHit hit: hits) {
                Map<String, Object> map = hit.getSource();
                this.result.add(map);
            }
            
            // evaluate aggregation
            // collect results: fields
            this.aggregations = new HashMap<>();
            for (String field: aggregationFields) {
                Terms fieldCounts = response.getAggregations().get(field);
                List<Bucket> buckets = fieldCounts.getBuckets();
                // aggregate double-tokens (matching lowercase)
                Map<String, Long> checkMap = new HashMap<>();
                for (Bucket bucket: buckets) {
                    String key = bucket.getKeyAsString().trim();
                    if (key.length() > 0) {
                        String k = key.toLowerCase();
                        Long v = checkMap.get(k);
                        checkMap.put(k, v == null ? bucket.getDocCount() : v + bucket.getDocCount());
                    }
                }
                ArrayList<Map.Entry<String, Long>> list = new ArrayList<>(buckets.size());
                for (Bucket bucket: buckets) {
                    String key = bucket.getKeyAsString().trim();
                    if (key.length() > 0) {
                        Long v = checkMap.remove(key.toLowerCase());
                        if (v == null) continue;
                        list.add(new AbstractMap.SimpleEntry<String, Long>(key, v));
                    }
                }
                aggregations.put(field, list);
                //if (field.equals("place_country")) {
                    // special handling of country aggregation: add the country center as well
                //}
            }
        }
    }
    
    
    public List<Map<String, Object>> queryWithConstraints(final String indexName, final String fieldName, final String fieldValue, final Map<String, String> constraints, boolean latest) throws IOException {
        SearchRequestBuilder request = this.elasticsearchClient.prepareSearch(indexName)
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setFrom(0);

        BoolQueryBuilder bFilter = QueryBuilders.boolQuery();
        bFilter.filter(QueryBuilders.constantScoreQuery(QueryBuilders.constantScoreQuery(QueryBuilders.termQuery(fieldName, fieldValue))));
        for (Object o : constraints.entrySet()) {
            @SuppressWarnings("rawtypes")
            Map.Entry entry = (Map.Entry) o;
            bFilter.filter(QueryBuilders.constantScoreQuery(QueryBuilders.termQuery((String) entry.getKey(), ((String) entry.getValue()).toLowerCase())));
        }
        request.setQuery(bFilter);
        
        // get response
        SearchResponse response = request.execute().actionGet();

        // evaluate search result
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        SearchHit[] hits = response.getHits().getHits();
        for (SearchHit hit: hits) {
            Map<String, Object> map = hit.getSource();
            result.add(map);
        }

        return result;
    }
    
    public static void main(String[] args) {
    	ElasticsearchClient client = new ElasticsearchClient(new String[]{"localhost:9300"}, "");
    	// check access
    	client.createIndexIfNotExists("test", 1, 0);
    	System.out.println(client.count("test"));
    	// upload a schema
    	try {
            String mapping = new String(Files.readAllBytes(Paths.get("conf/mappings/web.json")));
            client.setMapping("test", mapping);
        } catch (IOException e) {
            e.printStackTrace();
        }
    	
    	client.close();
    }
}

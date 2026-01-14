package org.smileyface.webcrawler.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.elasticsearch.indices.DeleteAliasRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutAliasRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutIndicesSettingsRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.smileyface.webcrawler.model.WebPageContent;
// Note: Not a Spring-managed bean by default; construct with ElasticContext where needed

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

/**
 * Thin wrapper around the Elasticsearch Java API client exposing a few admin operations
 * required by the application: indices, aliases, and templates.
 */
public class ElasticRestClient {

    private final ElasticsearchClient client;

    /**
     * Constructs the client using the provided ElasticContext by internally creating the underlying
     * Elasticsearch Java API client.
     */
    public ElasticRestClient(ElasticContext context) {
        if (context == null) {
            throw new IllegalArgumentException("ElasticContext must not be null");
        }
        RestClient lowLevel = RestClient.builder(new HttpHost(context.getAddress(), context.getPort(), "http"))
                .build();
        ElasticsearchTransport transport = new RestClientTransport(lowLevel, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
    }

    // ---------------- Indices ----------------

    /**
     * Creates an index if it does not exist.
     * @return true if created, false if already exists
     */
    public boolean createIndex(String indexName) throws IOException {
        try {
            boolean exists = client.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
            if (exists) return false;
            client.indices().create(CreateIndexRequest.of(b -> b.index(indexName)));
            return true;
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    /**
     * Creates an index with the provided JSON body (settings/mappings/aliases). The JSON should not include the index name.
     * @return true if created, false if already exists
     */
    public boolean createIndex(String indexName, String jsonBody) throws IOException {
        try {
            boolean exists = client.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
            if (exists) return false;
            client.indices().create(b -> b.index(indexName).withJson(new StringReader(jsonBody)));
            return true;
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    /**
     * Updates index settings using the JSON body (e.g. {"index": {"number_of_replicas": 1}}).
     */
    public void updateIndex(String indexName, String settingsJson) throws IOException {
        try {
            client.indices().putSettings(PutIndicesSettingsRequest.of(b -> b
                    .index(indexName)
                    .withJson(new StringReader(settingsJson))
            ));
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    /**
     * Deletes the given index if it exists.
     * @return true if deleted, false if it did not exist
     */
    public boolean deleteIndex(String indexName) throws IOException {
        try {
            boolean exists = client.indices().exists(ExistsRequest.of(b -> b.index(indexName))).value();
            if (!exists) return false;
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(indexName)));
            return true;
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    // ---------------- Aliases ----------------

    /**
     * Creates or updates an alias pointing to an index.
     */
    public void createAlias(String indexName, String aliasName) throws IOException {
        try {
            client.indices().putAlias(PutAliasRequest.of(b -> b.index(indexName).name(aliasName)));
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    /**
     * Deletes an alias from an index. No-op if alias doesn't exist.
     */
    public void deleteAlias(String indexName, String aliasName) throws IOException {
        try {
            client.indices().deleteAlias(DeleteAliasRequest.of(b -> b.index(indexName).name(aliasName)));
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    // ---------------- Templates (Composable Index Templates) ----------------

    /**
     * Creates or updates a composable index template using provided patterns and optional JSON body for the template
     * (settings/mappings/aliases). If templateJson is null, only name and patterns are set.
     */
    public void createTemplate(String templateName, List<String> indexPatterns, String templateJson) throws IOException {
        try {
            if (templateJson == null || templateJson.isBlank()) {
                client.indices().putIndexTemplate(PutIndexTemplateRequest.of(b -> b.name(templateName).indexPatterns(indexPatterns)));
            } else {
                client.indices().putIndexTemplate(b -> b
                        .name(templateName)
                        .withJson(new StringReader(templateJson))
                );
            }
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    /**
     * Deletes a composable index template by name. No-op if it does not exist.
     */
    public void deleteTemplate(String templateName) throws IOException {
        try {
            client.indices().deleteIndexTemplate(DeleteIndexTemplateRequest.of(b -> b.name(templateName)));
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    // ---------------- Documents ----------------

    /**
     * Indexes the given WebPageContent document into the specified index using the Elasticsearch Java API client.
     * If the document contains a non-blank id, it will be used as the document id; otherwise, Elasticsearch will
     * auto-generate one. Returns the id of the indexed document.
     *
     * @param indexName target index name
     * @param document  WebPageContent to index (serialized via Jackson)
     * @return the document id assigned by Elasticsearch
     */
    public String indexDocument(String indexName, WebPageContent document) throws IOException {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null/blank");
        }
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        try {
            String id = document.getId();
            IndexResponse resp = (id == null || id.isBlank())
                    ? client.index(b -> b.index(indexName).document(document))
                    : client.index(b -> b.index(indexName).id(id).document(document));
            return resp.id();
        } catch (ElasticsearchException e) {
            throw e;
        }
    }

    /**
     * Fetches a document by id from the specified index and deserializes it into WebPageContent.
     * Returns null if the document is not found.
     */
    public WebPageContent getDocument(String indexName, String id) throws IOException {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null/blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null/blank");
        }
        try {
            var resp = client.get(b -> b.index(indexName).id(id), WebPageContent.class);
            if(resp == null) {
                return null;
            } else {
                resp.source().setId(resp.id());
                return resp.source();
            }
        } catch (ElasticsearchException e) {
            throw e;
        }
    }
}

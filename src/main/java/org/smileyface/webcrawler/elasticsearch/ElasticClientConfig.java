package org.smileyface.webcrawler.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * Basic configuration that wires the Elasticsearch Java API client (co.elastic.clients:elasticsearch-java).
 * Supports multiple hosts and optional basic authentication.
 */
@Configuration
public class ElasticClientConfig {

    @Value("${elasticsearch.hosts:http://localhost:9200}")
    private List<String> hosts;

    @Value("${elasticsearch.username:}")
    private String username;

    @Value("${elasticsearch.password:}")
    private String password;

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchLowLevelClient() {
        List<HttpHost> httpHosts = new ArrayList<>();
        for (String h : hosts) {
            httpHosts.add(HttpHost.create(h));
        }

        RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[0]));

        if (username != null && !username.isBlank()) {
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(clientBuilder -> clientBuilder.setDefaultCredentialsProvider(creds));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient elasticsearchLowLevelClient) {
        return new RestClientTransport(elasticsearchLowLevelClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport elasticsearchTransport) {
        return new ElasticsearchClient(elasticsearchTransport);
    }
}

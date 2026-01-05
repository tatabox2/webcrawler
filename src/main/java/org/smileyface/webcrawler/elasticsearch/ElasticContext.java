package org.smileyface.webcrawler.elasticsearch;

import java.util.Objects;

/**
 * Simple context describing how to connect to an Elasticsearch cluster for a given tenant.
 * Contains a logical tenant id and the HTTP address/port of the Elasticsearch node.
 */
public final class ElasticContext {

    private final String tenantId;
    private final String address;
    private final int port;

    public ElasticContext(String tenantId, String address, int port) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address must not be null/blank");
        }
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        this.address = address.trim();
        this.port = port;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "ElasticContext{" +
                "tenantId='" + tenantId + '\'' +
                ", address='" + address + '\'' +
                ", port=" + port +
                '}';
    }
}

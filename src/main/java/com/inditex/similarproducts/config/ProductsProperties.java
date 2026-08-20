package com.inditex.similarproducts.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for the calls to the existing product APIs and for the fan-out.
 */
@ConfigurationProperties(prefix = "products")
public record ProductsProperties(

		/* Base url of the existing APIs (existingApis.yaml). */
		String baseUrl,

		/* Max time to establish a TCP connection with the existing APIs. */
		@DefaultValue("500ms") Duration connectTimeout,

		/* Max time to obtain the list of similar ids. */
		@DefaultValue("2s") Duration similarIdsTimeout,

		/* Max time to obtain a single product detail. */
		@DefaultValue("10s") Duration detailTimeout,

		/* Time budget for the whole detail fan-out; slower details are omitted from the response. */
		@DefaultValue("2s") Duration fanOutBudget,

		/* Max number of detail requests resolved in parallel per incoming request. */
		@DefaultValue("16") int fanOutConcurrency,

		/* Max number of pooled connections against the existing APIs. */
		@DefaultValue("1000") int maxConnections,

		/* Max time a request waits for a free connection from the pool. */
		@DefaultValue("2s") Duration pendingAcquireTimeout,

		/* Number of immediate retries for transient failures (network errors, timeouts, 5xx). */
		@DefaultValue("1") int maxRetries,

		/* Base delay of the exponential backoff between retries. */
		@DefaultValue("20ms") Duration retryBackoff,

		@DefaultValue CacheProperties cache) {

	public record CacheProperties(

			@DefaultValue("true") boolean enabled,

			/* How long a list of similar ids is served from memory. */
			@DefaultValue("60s") Duration similarIdsTtl,

			/* How long a product detail (including "not found") is served from memory. */
			@DefaultValue("300s") Duration detailTtl,

			/* Max entries kept per cache. */
			@DefaultValue("50000") long maxSize) {
	}
}

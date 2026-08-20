package com.inditex.similarproducts;

import java.time.Duration;

import com.inditex.similarproducts.config.ProductsProperties;
import com.inditex.similarproducts.config.ProductsProperties.CacheProperties;

/**
 * Builds {@link ProductsProperties} for tests without dragging the whole Spring context in.
 */
public final class TestProperties {

	private TestProperties() {
	}

	public static ProductsProperties with(String baseUrl, Duration timeout, int maxRetries) {
		return new ProductsProperties(baseUrl, Duration.ofMillis(500), timeout, timeout, Duration.ofSeconds(2), 16,
				100, Duration.ofSeconds(2), maxRetries, Duration.ofMillis(1),
				new CacheProperties(true, Duration.ofSeconds(60), Duration.ofSeconds(300), 1000));
	}
}

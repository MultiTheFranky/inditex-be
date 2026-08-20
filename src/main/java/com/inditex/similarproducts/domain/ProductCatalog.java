package com.inditex.similarproducts.domain;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read access to the product catalog. Implementations may talk to the remote APIs, add caching or
 * any other cross-cutting concern, keeping the domain service unaware of the transport.
 */
public interface ProductCatalog {

	/**
	 * Ids of the products similar to the given one, ordered by similarity. Fails with
	 * {@link ProductNotFoundException} when the product does not exist.
	 */
	Flux<String> similarIds(String productId);

	/**
	 * Detail of a product, empty when the product does not exist.
	 */
	Mono<ProductDetail> detail(String productId);
}

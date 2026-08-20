package com.inditex.similarproducts.domain;

/**
 * The requested product does not exist in the catalog.
 */
public class ProductNotFoundException extends RuntimeException {

	private final String productId;

	public ProductNotFoundException(String productId) {
		super("Product '%s' not found".formatted(productId));
		this.productId = productId;
	}

	public String productId() {
		return productId;
	}
}

package com.inditex.similarproducts.domain;

/**
 * Detail of a product as exposed by the public API contract (similarProducts.yaml).
 */
public record ProductDetail(String id, String name, Double price, Boolean availability) {
}

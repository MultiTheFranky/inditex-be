package com.inditex.similarproducts.domain;

/**
 * The catalog could not be reached (timeout, connection error, upstream 5xx or open circuit).
 */
public class CatalogUnavailableException extends RuntimeException {

	public CatalogUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}

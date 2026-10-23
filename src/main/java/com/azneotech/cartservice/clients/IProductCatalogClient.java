package com.azneotech.cartservice.clients;

import com.azneotech.cartservice.dtos.ProductCatalogDto;

public interface IProductCatalogClient {

    /**
     * @throws com.azneotech.cartservice.exceptions.ProductNotFoundException if the catalog has no such product
     * @throws com.azneotech.cartservice.exceptions.DownstreamUnavailableException if product-catalog-service cannot answer
     */
    ProductCatalogDto getProduct(Long productId);

}

package com.azneotech.cartservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** The slice of ProductCatalogService's {@code GET /products/{id}} response the cart uses; the rest is ignored. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductCatalogDto {

    private Long id;
    private String title;
    private BigDecimal price;
    private String image;

}

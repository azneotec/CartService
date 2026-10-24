package com.azneotech.cartservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderItemResponseDto {

    private Long productId;
    private String title;
    private String imageUrl;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;

}

package com.xaan.demo.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class BoardSummaryDto {
    private Date postDate;
    private Long contentSize;
    private Long articles;
    private String lastTime;
}

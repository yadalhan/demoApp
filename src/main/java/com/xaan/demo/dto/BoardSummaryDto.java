package com.xaan.demo.dto;

import java.util.Date;

public interface BoardSummaryDto {
    Date getPostDate();
    Long getContentSize();
    Long getArticles();
    String getLastTime();
}

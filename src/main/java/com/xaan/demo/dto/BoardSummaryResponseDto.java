package com.xaan.demo.dto;

import java.io.Serializable;
import java.util.Date;

public class BoardSummaryResponseDto implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Date postDate;
    private Long contentSize;
    private Long articles;
    private String lastTime;

    public BoardSummaryResponseDto() {}

    public BoardSummaryResponseDto(Date postDate, Long contentSize, Long articles, String lastTime) {
        this.postDate = postDate;
        this.contentSize = contentSize;
        this.articles = articles;
        this.lastTime = lastTime;
    }

    public Date getPostDate() {
        return postDate;
    }

    public void setPostDate(Date postDate) {
        this.postDate = postDate;
    }

    public Long getContentSize() {
        return contentSize;
    }

    public void setContentSize(Long contentSize) {
        this.contentSize = contentSize;
    }

    public Long getArticles() {
        return articles;
    }

    public void setArticles(Long articles) {
        this.articles = articles;
    }

    public String getLastTime() {
        return lastTime;
    }

    public void setLastTime(String lastTime) {
        this.lastTime = lastTime;
    }
}

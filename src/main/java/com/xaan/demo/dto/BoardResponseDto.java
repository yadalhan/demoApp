package com.xaan.demo.dto;

import com.xaan.demo.domain.entity.Board;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class BoardResponseDto {
    private Long   id;
    private String title;
    private String content;
    private String author;

    private LocalDateTime createdDate;
    private LocalDateTime modifiedDate;

    public BoardResponseDto(Board entity){
        this.id      = entity.getId();
        this.title   = entity.getTitle();
        this.content = entity.getContent();
        this.author  = entity.getAuthor();

        this.createdDate  = entity.getCreatedDate();
        this.modifiedDate  = entity.getModifiedDate();
    }
}

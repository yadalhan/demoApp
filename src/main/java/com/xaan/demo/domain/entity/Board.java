package com.xaan.demo.domain.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class Board extends BaseTimeEntity {
    private Long id;

    private String title;

    private String content;

    private String author;

    private String password;

    @Builder //builder pattern class
    public Board(String title, String content, String author, String password) {
        this.title   = title;
        this.content = content;
        this.author  = author;
        this.password = password;
}
}
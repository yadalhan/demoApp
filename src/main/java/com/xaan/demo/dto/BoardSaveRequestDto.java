package com.xaan.demo.dto;

import com.xaan.demo.domain.entity.Board;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class BoardSaveRequestDto {
    private String title;
    private String content;
    private String author;
    private String password;

    public Board toEntity() {
        return Board.builder()
                .title(title)
                .content(content)
                .author(author)
                .password(password)
                .build();
    }
}

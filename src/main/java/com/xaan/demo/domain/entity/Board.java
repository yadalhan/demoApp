package com.xaan.demo.domain.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity

public class Board extends BaseTimeEntity {
    @Id // PK (Primary Key)
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Auto Increment
    private Long id;

    @Column(length = 500, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;
    
    @Column(length = 100)
    private String author;
    
    @Column(length = 255)
    private String password;

    @Builder //builder pattern class
    public Board(String title, String content, String author, String password) {
        this.title   = title;
        this.content = content;
        this.author  = author;
        this.password = password;
}
    
    public void update(String title, String content) {
        this.title = title;
        this.content = content;
    }
    
    public void updatePassword(String password) {
        this.password = password;
    }
}
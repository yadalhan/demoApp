package com.xaan.demo.domain.entity;

import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public abstract class BaseTimeEntity {
  private LocalDateTime createdDate;

  private LocalDateTime modifiedDate;
}

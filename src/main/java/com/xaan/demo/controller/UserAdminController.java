package com.xaan.demo.controller;

import com.xaan.demo.dto.UserResponseDto;
import com.xaan.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RequiredArgsConstructor
@Controller
public class UserAdminController {
    private final UserService userService;

    // 사용자 목록/검색 - 이름은 부분 일치, 전화번호/주민등록번호는 blind index를 통한 정확 일치
    @GetMapping("/users")
    public String list(@RequestParam(required = false) String name,
                        @RequestParam(required = false) String phone,
                        @RequestParam(required = false) String residentRegistrationNumber,
                        Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        List<UserResponseDto> users = userService.search(name, phone, residentRegistrationNumber);
        model.addAttribute("users", users);
        model.addAttribute("name", name);
        model.addAttribute("phone", phone);
        model.addAttribute("residentRegistrationNumber", residentRegistrationNumber);
        return "users/list";
    }
}

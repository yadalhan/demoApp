package com.xaan.demo.controller;

import com.xaan.demo.dto.LoginRequestDto;
import com.xaan.demo.dto.UserRegisterRequestDto;
import com.xaan.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;

    @GetMapping("/login")
    public String loginForm() {
        return "auth/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String userId, @RequestParam String password, HttpSession session, Model model) {
        if (userService.validateLogin(userId, password)) {
            session.setAttribute("loginUser", userId);
            return "redirect:/";
        }
        model.addAttribute("error", "아이디 또는 비밀번호가 일치하지 않습니다.");
        return "auth/login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @GetMapping("/register")
    public String registerForm() {
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String userId, 
                          @RequestParam String username, 
                          @RequestParam String password,
                          Model model) {
        try {
            UserRegisterRequestDto dto = new UserRegisterRequestDto();
            dto.setUserId(userId);
            dto.setUsername(username);
            dto.setPassword(password);
            userService.register(dto);
            return "redirect:/login?registered";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/register";
        }
    }
}
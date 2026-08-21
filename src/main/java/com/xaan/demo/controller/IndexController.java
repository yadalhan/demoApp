package com.xaan.demo.controller;

import com.xaan.demo.service.BoardService;
import com.xaan.demo.dto.BoardResponseDto;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;

@RequiredArgsConstructor
@Controller
public class IndexController {
    private final BoardService boardService;

    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("posts", boardService.findLast100());
        return "last100";
    }

    @GetMapping("/posts/save")
    public String saveForm(HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        return "posts/save";
    }

    @GetMapping("/posts/update/{id}")
    public String updateForm(@PathVariable Long id, Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        model.addAttribute("post", boardService.findById(id));
        return "posts/update";
    }

    // 게시글 상세보기 - 비밀번호가 설정된 글은 세션에서 확인된 경우에만 내용을 보여준다
    @GetMapping("/posts/{id}")
    public String viewPost(@PathVariable Long id, Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        BoardResponseDto post = boardService.findById(id);
        boolean verified = !post.isPasswordProtected()
                || Boolean.TRUE.equals(session.getAttribute("verifiedPost:" + id));
        model.addAttribute("post", post);
        model.addAttribute("verified", verified);
        return "posts/view";
    }

    // 비밀번호 확인 팝업 - 상세보기(메인 화면)와 분리된 별도 팝업 창에서만 비밀번호를 입력받는다
    @GetMapping("/posts/{id}/verify-popup")
    public String showVerifyPopup(@PathVariable Long id, Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        model.addAttribute("postId", id);
        return "posts/verify-popup";
    }

    // 비밀번호 확인 제출 - 맞으면 세션에 확인 표시를 남기고 팝업이 스스로 닫히며 메인 화면(상세보기)을 새로고침한다
    @PostMapping("/posts/{id}/verify-popup")
    public String submitVerifyPopup(@PathVariable Long id, @RequestParam String password,
                                     HttpSession session, Model model) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        if (boardService.verifyPassword(id, password)) {
            session.setAttribute("verifiedPost:" + id, Boolean.TRUE);
            return "posts/verify-popup-success";
        }
        model.addAttribute("postId", id);
        model.addAttribute("passwordError", true);
        return "posts/verify-popup";
    }

    @GetMapping("/list1st")
    public String getBoardList(@PageableDefault(size = 10) Pageable pageable, Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        Page<BoardResponseDto> posts = boardService.getBoardList(pageable);
        model.addAttribute("posts", posts.getContent());
        return "list1st";
    }

    @GetMapping("/list1stonly")
    public String getBoardList1stOnly(@PageableDefault(size = 10) Pageable pageable, Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        Slice<BoardResponseDto> posts = boardService.getBoardList1stOnly(pageable);
        model.addAttribute("posts", posts.getContent());
        return "list1stonly";
    }

    @GetMapping("/bbs_summary")
    public String bbsSummary(Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        model.addAttribute("summaries", boardService.getBoardSummary());
        return "bbs_summary";
    }

    @GetMapping("/bbs_summary_cached")
    public String bbsSummaryCached(Model model, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }
        model.addAttribute("summaries", boardService.getBoardSummaryCached());
        return "bbs_summary";
    }
}

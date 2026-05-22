package com.ecom.controller;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import com.ecom.model.Category;
import com.ecom.model.Product;
import com.ecom.model.UserDtls;
import com.ecom.service.CartService;
import com.ecom.service.CategoryService;
import com.ecom.service.ProductService;
import com.ecom.service.UserService;
import com.ecom.util.CommonUtil;
import org.springframework.web.multipart.MultipartFile; 

import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@Controller
public class HomeController {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    @Autowired
    private UserService userService;

    @Autowired
    private CommonUtil commonUtil;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private CartService cartService;

    // Common Data
    @ModelAttribute
    public void getUserDetails(Principal p, Model m) {
        if (p != null) {
            String email = p.getName();
            UserDtls userDtls = userService.getUserByEmail(email);
            m.addAttribute("user", userDtls);

            Integer countCart = cartService.getCountCart(userDtls.getId());
            m.addAttribute("countCart", countCart);
        }

        List<Category> allActiveCategory = categoryService.getAllActiveCategory();
        m.addAttribute("categorys", allActiveCategory);
    }

    // Home Page
    @GetMapping("/")
    public String index(Model m) {

        List<Category> categories = categoryService.getAllActiveCategory()
                .stream()
                .sorted((c1, c2) -> c2.getId().compareTo(c1.getId()))
                .limit(6)
                .toList();

        List<Product> products = productService.getAllActiveProducts("")
                .stream()
                .sorted((p1, p2) -> p2.getId().compareTo(p1.getId()))
                .limit(8)
                .toList();

        m.addAttribute("category", categories);
        m.addAttribute("products", products);

        return "index";
    }

    // Login & Register
    @GetMapping("/signin")
    public String login() {
        return "login";
    }
    


    @GetMapping("/register")
    public String register() {
        return "register";
    }

    // ✅ PRODUCTS WITH SEARCH + PAGINATION
    @GetMapping("/products")
    public String products(Model m,
            @RequestParam(value = "category", defaultValue = "") String category,
            @RequestParam(name = "pageNo", defaultValue = "0") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "12") Integer pageSize,
            @RequestParam(defaultValue = "") String ch) {

        List<Category> categories = categoryService.getAllActiveCategory();
        m.addAttribute("categories", categories);
        m.addAttribute("paramValue", category);
        m.addAttribute("ch", ch);

        Page<Product> page;

        // ✅ FIXED SEARCH CONDITION
        if (ch == null || ch.trim().isEmpty()) {
            page = productService.getAllActiveProductPagination(pageNo, pageSize, category);
        } else {
            page = productService.searchActiveProductPagination(pageNo, pageSize, category, ch);
        }

        m.addAttribute("products", page.getContent());
        m.addAttribute("productsSize", page.getContent().size());

        m.addAttribute("pageNo", page.getNumber());
        m.addAttribute("pageSize", pageSize);
        m.addAttribute("totalElements", page.getTotalElements());
        m.addAttribute("totalPages", page.getTotalPages());
        m.addAttribute("isFirst", page.isFirst());
        m.addAttribute("isLast", page.isLast());

        return "product";
    }

    // View Single Product
    @GetMapping("/product/{id}")
    public String product(@PathVariable int id, Model m) {
        Product product = productService.getProductById(id);
        m.addAttribute("product", product);
        return "view_product";
    }

    // Save User
    @PostMapping("/saveUser")
    public String saveUser(@ModelAttribute UserDtls user,
                           @RequestParam("img") MultipartFile file,
                           HttpSession session) throws IOException {

        Boolean existsEmail = userService.existsEmail(user.getEmail());

        if (existsEmail) {
            session.setAttribute("errorMsg", "Email already exist");
        } else {

            String imageName = file.isEmpty() ? "default.jpg" : file.getOriginalFilename();
            user.setProfileImage(imageName);

            UserDtls saveUser = userService.saveUser(user);

            if (!ObjectUtils.isEmpty(saveUser)) {

                if (!file.isEmpty()) {
                    File saveFile = new ClassPathResource("static/img").getFile();

                    Path path = Paths.get(saveFile.getAbsolutePath()
                            + File.separator + "profile_img"
                            + File.separator + file.getOriginalFilename());

                    Files.copy(file.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
                }

                session.setAttribute("succMsg", "Register successfully");
            } else {
                session.setAttribute("errorMsg", "Something went wrong on server");
            }
        }

        return "redirect:/register";
    }

    // Forgot Password
    @GetMapping("/forgot-password")
    public String showForgotPassword() {
        return "forgot_password";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String email,
                                        HttpSession session,
                                        HttpServletRequest request)
            throws MessagingException, UnsupportedEncodingException {

        UserDtls user = userService.getUserByEmail(email);

        if (ObjectUtils.isEmpty(user)) {
            session.setAttribute("errorMsg", "Invalid email");
        } else {

            String token = UUID.randomUUID().toString();
            userService.updateUserResetToken(email, token);

            String url = CommonUtil.generateUrl(request) + "/reset-password?token=" + token;

            Boolean sendMail = commonUtil.sendMail(url, email);

            if (sendMail) {
                session.setAttribute("succMsg", "Check your email for reset link");
            } else {
                session.setAttribute("errorMsg", "Email not sent");
            }
        }

        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String showResetPassword(@RequestParam String token, Model m) {

        UserDtls user = userService.getUserByToken(token);

        if (user == null) {
            m.addAttribute("msg", "Invalid or expired link");
            return "message";
        }

        m.addAttribute("token", token);
        return "reset_password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                @RequestParam String password,
                                Model m) {

        UserDtls user = userService.getUserByToken(token);

        if (user == null) {
            m.addAttribute("errorMsg", "Invalid or expired link");
        } else {
            user.setPassword(passwordEncoder.encode(password));
            user.setResetToken(null);
            userService.updateUser(user);

            m.addAttribute("msg", "Password changed successfully");
        }

        return "message";
    }
    
 // feedback
    @GetMapping("/feedback")
    public String feedback() {
        return "feedback";
    }
}
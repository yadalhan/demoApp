#!/bin/bash

# GitHub Upload Script for demoApp Project
# This script provides step-by-step instructions to upload the demoApp project to GitHub

echo "=============================================="
echo "GitHub Upload Instructions for demoApp Project"
echo "=============================================="
echo ""
echo "The demoApp project has been prepared for GitHub with:"
echo "✅ Git repository initialized"
echo "✅ .gitignore configured for Spring Boot/Java"
echo "✅ Initial commit created"
echo "✅ README.md documentation added"
echo ""
echo "To upload to GitHub (https://github.com/yadalhan), follow these steps:"
echo ""

# Step 1: Check current git status
echo "1. CURRENT GIT STATUS:"
git status
echo ""

# Step 2: Check commit history
echo "2. COMMIT HISTORY:"
git log --oneline
echo ""

# Step 3: Instructions for GitHub authentication
echo "3. GITHUB AUTHENTICATION:"
echo "   If you haven't authenticated with GitHub CLI:"
echo "   Run: gh auth login"
echo "   Follow the prompts to authenticate with your GitHub account."
echo ""

# Step 4: Create GitHub repository
echo "4. CREATE GITHUB REPOSITORY:"
echo "   Choose a repository name (e.g., 'demoApp'):"
echo "   Run: gh repo create yadalhan/demoApp --public --source=. --remote=origin --push"
echo ""
echo "   OR manually create at: https://github.com/new"
echo "   Then add remote: git remote add origin https://github.com/yadalhan/demoApp.git"
echo ""

# Step 5: Push to GitHub
echo "5. PUSH TO GITHUB:"
echo "   After setting up remote:"
echo "   Run: git push -u origin master"
echo ""
echo "   To rename branch to 'main' (GitHub default):"
echo "   git branch -M main"
echo "   git push -u origin main"
echo ""

# Step 6: Alternative method with personal access token
echo "6. ALTERNATIVE METHOD (Using Personal Access Token):"
echo "   Create a PAT at: https://github.com/settings/tokens"
echo "   Then push using:"
echo "   git remote add origin https://[YOUR_TOKEN]@github.com/yadalhan/demoApp.git"
echo "   git push -u origin master"
echo ""

# Step 7: Quick command summary
echo "7. QUICK COMMAND SUMMARY:"
echo "   # Authenticate with GitHub CLI"
echo "   gh auth login"
echo ""
echo "   # Create repository and push (all-in-one)"
echo "   gh repo create yadalhan/demoApp --public --source=. --remote=origin --push"
echo ""
echo "   # OR manually:"
echo "   git remote add origin https://github.com/yadalhan/demoApp.git"
echo "   git push -u origin master"
echo ""

# Step 8: Verify
echo "8. VERIFY UPLOAD:"
echo "   After pushing, visit: https://github.com/yadalhan/demoApp"
echo "   You should see your project files there."
echo ""

echo "=============================================="
echo "Repository Information:"
echo "=============================================="
echo "Repository URL: https://github.com/yadalhan/demoApp"
echo "Project: Spring Boot demo application"
echo "Commit Count: $(git rev-list --count HEAD)"
echo "Files: $(git ls-files | wc -l)"
echo "=============================================="
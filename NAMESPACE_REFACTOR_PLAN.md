# Spola Namespace Refactor Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Refactor the spola-software project to use the dev.spola namespace instead of dev.spola, updating all Kotlin source files, Gradle configurations, and related resources.

**Approach:** 
1. First, identify all files containing the dev.spola namespace
2. Systematically replace dev.spola with dev.spola in Kotlin source files
3. Update Gradle group identifiers in build.gradle.kts files
4. Update any resource files that may contain the namespace
5. Verify the build and run tests to ensure nothing is broken

**Tech Stack:** Kotlin, Gradle, Git

---

### Task 1: Explore the codebase to find all occurrences of dev.spola

**Objective:** Identify all files that need to be updated during the namespace refactor.

**Files:**
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.kt", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.kts", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.xml", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.yml", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.yaml", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.json", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.md", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.txt", limit=100)`
- Search: `search_files("dev.spola", target="content", path=".", file_glob="*.properties", limit=100)`

**Step 1: Run searches to collect file paths**

We'll execute these searches and save the results to a temporary file for reference.

**Step 2: Analyze the results and categorize by file type**

**Step 3: Commit the exploration results**

```bash
git add .hermes/plans/namespace-refactor-exploration.md 2>/dev/null || true
git commit -m "docs: add namespace refactor exploration results" 2>/dev/null || true
```

---

### Task 2: Update Kotlin source files in spola-backend

**Objective:** Replace all instances of `dev.spola` with `dev.spola` in Kotlin source files under spola-backend.

**Files:**
- Modify: `spola-backend/**/*.kt` (all Kotlin files in the backend module)

**Step 1: Write a script to perform the replacement**

We'll use a combination of find and sed to replace the namespace in Kotlin files.

**Step 2: Run the replacement on Kotlin files**

```bash
find spola-backend -type f -name "*.kt" -exec sed -i 's/\bdev\.golem\b/dev.spola/g' {} +
```

**Step 3: Verify the changes**

Run a search to ensure no instances of dev.spola remain in Kotlin files.

```bash
find spola-backend -type f -name "*.kt" -exec grep -l "dev\.golem" {} \;
```

**Step 4: Commit the changes**

```bash
git add spola-backend/
git commit -m "refactor: update namespace to dev.spola in backend Kotlin sources"
```

---

### Task 3: Update Kotlin source files in spola-frontend

**Objective:** Replace all instances of `dev.spola` with `dev.spola` in Kotlin source files under spola-frontend.

**Files:**
- Modify: `spola-frontend/**/*.kt` (all Kotlin files in the frontend module)

**Step 1: Write a script to perform the replacement**

**Step 2: Run the replacement on Kotlin files**

```bash
find spola-frontend -type f -name "*.kt" -exec sed -i 's/\bdev\.golem\b/dev.spola/g' {} +
```

**Step 3: Verify the changes**

```bash
find spola-frontend -type f -name "*.kt" -exec grep -l "dev\.golem" {} \;
```

**Step 4: Commit the changes**

```bash
git add spola-frontend/
git commit -m "refactor: update namespace to dev.spola in frontend Kotlin sources"
```

---

### Task 4: Update Gradle build files

**Objective:** Update the group identifier in build.gradle.kts files to dev.spola.

**Files:**
- Modify: `spola-backend/build.gradle.kts`
- Modify: `spola-frontend/build.gradle.kts`
- Modify: `spola-backend/shared/build.gradle.kts` (if exists)
- Modify: `settings.gradle.kts` (root)
- Modify: `build.gradle.kts` (root, if exists)

**Step 1: Update each build.gradle.kts file**

For each file, change the line that sets the group (typically `group = "dev.spola"`) to `group = "dev.spola"`.

Example:
```kotlin
group = "dev.spola"
version = "0.0.1"
```

**Step 2: Also update any occurrences in other Gradle files (like gradle.properties)**

**Step 3: Verify the changes**

Search for any remaining dev.spola in Gradle files.

```bash
find . -name "*.kts" -exec grep -l "dev\.golem" {} \;
```

**Step 4: Commit the changes**

```bash
git add spola-backend/build.gradle.kts spola-frontend/build.gradle.kts settings.gradle.kts
git commit -m "refactor: update Gradle group to dev.spola"
```

---

### Task 5: Update resource files and configuration files

**Objective:** Check and update any resource files (like AndroidManifest.xml, plugin configurations, etc.) that may contain the namespace.

**Files:**
- Modify: `spola-frontend/src/androidMain/AndroidManifest.xml` (if exists)
- Modify: `spola-frontend/src/desktopMain/` resources
- Modify: `spola-backend/src/main/resources/` (if any)
- Modify: `*.proto` files (if any)
- Modify: `plugin.xml` or similar plugin descriptors

**Step 1: Search for dev.spola in resource files**

```bash
find . -type f \( -name "*.xml" -o -name "*.proto" -o -name "*.properties" \) -exec grep -l "dev\.golem" {} \;
```

**Step 2: For each file found, replace dev.spola with dev.spola**

```bash
find . -type f \( -name "*.xml" -o -name "*.proto" -o -name "*.properties" \) -exec sed -i 's/\bdev\.golem\b/dev.spola/g' {} +
```

**Step 3: Verify the changes**

```bash
find . -type f \( -name "*.xml" -o -name "*.proto" -o -name "*.properties" \) -exec grep -l "dev\.golem" {} \;
```

**Step 4: Commit the changes**

```bash
git add . # (carefully, or specify the resource files)
git commit -m "refactor: update namespace in resource files"
```

---

### Task 6: Update any remaining text files (docs, configs, etc.)

**Objective:** Update documentation, configuration files, and other text files that may reference the old namespace.

**Files:**
- Modify: `*.md` files (README, AGENTS.md, etc.)
- Modify: `*.txt` files
- Modify: `*.json` files (if they contain the namespace as a string)
- Modify: `*.yml` / `*.yaml` files (CI configs, etc.)

**Step 1: Search for dev.spola in text files**

```bash
find . -type f \( -name "*.md" -o -name "*.txt" -o -name "*.json" -o -name "*.yml" -o -name "*.yaml" \) -exec grep -l "dev\.golem" {} \;
```

**Step 2: Replace dev.spola with dev.spola in these files**

```bash
find . -type f \( -name "*.md" -o -name "*.txt" -o -name "*.json" -o -name "*.yml" -o -name "*.yaml" \) -exec sed -i 's/\bdev\.golem\b/dev.spola/g' {} +
```

**Step 3: Verify the changes**

```bash
find . -type f \( -name "*.md" -o -name "*.txt" -o -name "*.json" -o -name "*.yml" -o -name "*.yaml" \) -exec grep -l "dev\.golem" {} \;
```

**Step 4: Commit the changes**

```bash
git add .
git commit -m "refactor: update namespace in documentation and config files"
```

---

### Task 7: Verify the build and run tests

**Objective:** Ensure the project still builds and passes tests after the namespace refactor.

**Files:**
- Test: Build the project with Gradle
- Test: Run unit tests (if any)

**Step 1: Set up Java 21 (if not already)**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.7-tem
```

**Step 2: Build the project**

```bash
./gradlew build --no-parallel
```

**Step 3: Run tests**

```bash
./gradlew test --no-parallel
```

**Step 4: If build or tests fail, investigate and fix**

**Step 5: Commit any fixes**

```bash
git add .
git commit -m "fix: resolve build/test issues after namespace refactor"
```

---

### Task 8: Final verification and cleanup

**Objective:** Do a final check to ensure no dev.spola remnants exist and the project is ready.

**Step 1: Search for any remaining dev.spola in the entire project**

```bash
find . -type f -not -path "./.git/*" -exec grep -l "dev\.golem" {} \; || echo "No dev.spola found"
```

**Step 2: If any are found, repeat the relevant update task**

**Step 3: Update the README or documentation to reflect the new namespace**

**Step 4: Commit final changes**

```bash
git add .
git commit -m "refactor: final verification and cleanup"
```

---

**Note:** This plan assumes a standard Kotlin Multiproject Gradle structure. Adjust paths as necessary based on the actual project structure discovered in Task 1.

**Verification:** After completing all tasks, the project should build successfully, and all source code should use the dev.spola namespace.

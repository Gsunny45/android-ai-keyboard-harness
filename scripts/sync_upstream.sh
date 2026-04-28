#!/usr/bin/env bash
# sync_upstream.sh — Pull FlorisBoard upstream build scaffolding into this repo
#
# Fetches the pinned upstream tag, copies the full source tree and build
# infrastructure, then re-applies the AI plugin classes on top.
#
# Usage: ./scripts/sync_upstream.sh
set -euo pipefail

UPSTREAM_REMOTE="${1:-upstream}"
PINNED_TAG="${2:-v0.4.6}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKTREE_PATH="$(mktemp -d 2>/dev/null || echo "/tmp/florisboard-sync-$$")"

# Cleanup on exit
cleanup() {
    rm -rf "$WORKTREE_PATH" 2>/dev/null || true
    git -C "$REPO_ROOT" worktree remove "$WORKTREE_PATH" 2>/dev/null || true
}
trap cleanup EXIT

echo "============================================"
echo " FlorisBoard Upstream Sync ($PINNED_TAG)"
echo "============================================"
echo ""

# ── Step 1: Fetch upstream ──────────────────────────────────────────
echo "[1/5] Fetching upstream ($UPSTREAM_REMOTE)..."
git -C "$REPO_ROOT" fetch "$UPSTREAM_REMOTE" --tags 2>&1
echo ""

# ── Step 2: Check out pinned tag into temporary worktree ────────────
echo "[2/5] Checking out $PINNED_TAG into temporary worktree..."
git -C "$REPO_ROOT" worktree add "$WORKTREE_PATH" "$PINNED_TAG" 2>&1
echo ""

UPSTREAM_APP_SRC="$WORKTREE_PATH/app/src/main"
OUR_AI_SRC="$REPO_ROOT/app/src/main/java/dev/patrickgold/florisboard/ime/ai"
OUR_TEMPLATES="$REPO_ROOT/templates"

# ── Step 3: Confirm overwrite ────────────────────────────────────────
echo "[3/5] The following will be OVERWRITTEN with upstream content:"
echo "  • build.gradle.kts, settings.gradle.kts, gradle.properties"
echo "  • gradlew, gradlew.bat, gradle/wrapper/, gradle/libs.versions.toml"
echo "  • app/build.gradle.kts, app/proguard-rules.pro"
echo "  • app/src/main/ (kotlin source, resources, assets, manifest)"
echo "  • lib/, libnative/ (library dependencies)"
echo "  • .editorconfig, .gitattributes"
echo ""
echo "The AI plugin classes will be RE-APPLIED afterwards:"
echo "  • $OUR_AI_SRC"
echo "  • $OUR_TEMPLATES"
echo ""
read -p "Proceed with sync? (y/N) " -r CONFIRM
if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
fi
echo ""

# ── Step 4: Copy upstream files ──────────────────────────────────────
echo "[4/5] Copying upstream build infrastructure..."

# Root-level files
cp "$WORKTREE_PATH/build.gradle.kts" "$REPO_ROOT/build.gradle.kts"
cp "$WORKTREE_PATH/settings.gradle.kts" "$REPO_ROOT/settings.gradle.kts"
cp "$WORKTREE_PATH/gradle.properties" "$REPO_ROOT/gradle.properties"
cp "$WORKTREE_PATH/gradlew" "$REPO_ROOT/gradlew"
cp "$WORKTREE_PATH/gradlew.bat" "$REPO_ROOT/gradlew.bat"
chmod +x "$REPO_ROOT/gradlew"
chmod +x "$REPO_ROOT/gradlew.bat"

# Editor config
cp "$WORKTREE_PATH/.editorconfig" "$REPO_ROOT/.editorconfig" 2>/dev/null || true
cp "$WORKTREE_PATH/.gitattributes" "$REPO_ROOT/.gitattributes" 2>/dev/null || true

# Gradle wrapper + version catalog
mkdir -p "$REPO_ROOT/gradle/wrapper"
cp "$WORKTREE_PATH/gradle/wrapper/gradle-wrapper.properties" "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties"
cp "$WORKTREE_PATH/gradle/wrapper/gradle-wrapper.jar" "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar"
cp "$WORKTREE_PATH/gradle/libs.versions.toml" "$REPO_ROOT/gradle/libs.versions.toml"

echo "[4/5] Copying upstream app/ build files..."
cp "$WORKTREE_PATH/app/build.gradle.kts" "$REPO_ROOT/app/build.gradle.kts"
cp "$WORKTREE_PATH/app/proguard-rules.pro" "$REPO_ROOT/app/proguard-rules.pro"
cp "$WORKTREE_PATH/app/lint.xml" "$REPO_ROOT/app/lint.xml" 2>/dev/null || true

echo "[4/5] Copying upstream app/src/main/ (source, resources, manifest)..."
mkdir -p "$REPO_ROOT/app/src/main"
rsync -a --delete \
    --exclude='java/' \
    "$WORKTREE_PATH/app/src/main/" \
    "$REPO_ROOT/app/src/main/" 2>/dev/null || {
    # Fallback: cp with explicit exclusions
    find "$WORKTREE_PATH/app/src/main" -maxdepth 1 -not -name 'java' -exec cp -r {} "$REPO_ROOT/app/src/main/" \; 2>/dev/null || {
        echo "WARNING: Falling back to direct copy. java/ directory (if any) will be preserved."
        cp -r "$WORKTREE_PATH/app/src/main/"* "$REPO_ROOT/app/src/main/"
    }
}

echo "[4/5] Copying upstream library modules..."
for MODULE in lib libnative; do
    if [ -d "$WORKTREE_PATH/$MODULE" ]; then
        rm -rf "$REPO_ROOT/$MODULE"
        cp -r "$WORKTREE_PATH/$MODULE" "$REPO_ROOT/$MODULE"
        echo "  • $MODULE/ copied"
    fi
done

echo "[4/5] Copying utils/ (build utilities)..."
if [ -d "$WORKTREE_PATH/utils" ]; then
    rm -rf "$REPO_ROOT/utils"
    cp -r "$WORKTREE_PATH/utils" "$REPO_ROOT/utils"
fi

# ── Step 5: Re-apply AI plugin classes ──────────────────────────────
echo ""
echo "[5/5] Re-applying AI plugin classes..."

AI_CONFLICTS=false

if [ -d "$OUR_AI_SRC" ]; then
    # Verify AI source is intact
    AI_FILE_COUNT=$(find "$OUR_AI_SRC" -name "*.kt" | wc -l)
    echo "  • AI .kt files present: $AI_FILE_COUNT"

    # Check for any files that upstream may have placed at conflicting paths
    # (unlikely since upstream uses kotlin/ source root, not java/)
    UPSTREAM_CONFLICT=$(find "$WORKTREE_PATH/app/src/main/kotlin" -path "*/ime/ai/*" -name "*.kt" 2>/dev/null | head -5)
    if [ -n "$UPSTREAM_CONFLICT" ]; then
        echo "  ⚠ WARNING: Upstream has files at conflicting paths:"
        echo "$UPSTREAM_CONFLICT"
        AI_CONFLICTS=true
    else
        echo "  ✓ No conflicts detected in upstream source"
    fi
else
    echo "  ⚠ WARNING: AI source not found at $OUR_AI_SRC"
    echo "  Creating directory structure..."
    mkdir -p "$OUR_AI_SRC"
    AI_CONFLICTS=true
fi

# Re-apply templates
if [ -d "$OUR_TEMPLATES" ]; then
    TEMPLATE_COUNT=$(find "$OUR_TEMPLATES" -name "*.json" | wc -l)
    echo "  • Template files present: $TEMPLATE_COUNT"
else
    echo "  ⚠ WARNING: Templates not found at $OUR_TEMPLATES"
    mkdir -p "$OUR_TEMPLATES"
fi

echo ""
echo "============================================"
echo " Sync complete!"
echo "============================================"

if [ "$AI_CONFLICTS" = true ]; then
    echo ""
    echo "⚠ CONFLICTS DETECTED — review the warnings above."
    echo "   Run ./gradlew :app:tasks to verify the build loads."
else
    echo ""
    echo "✓ AI classes intact, no conflicts."
    echo "  Run ./gradlew :app:tasks to verify the build loads."
fi

echo ""
echo "Next steps:"
echo "  1. Update applicationId in app/build.gradle.kts"
echo "  2. Update namespace in app/build.gradle.kts"
echo "  3. Update .gitignore with Android patterns"
echo "  4. Run ./gradlew :app:tasks"

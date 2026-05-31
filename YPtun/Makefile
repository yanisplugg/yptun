# Olcbox macOS VPN Client Build Automation
# This Makefile handles building KMP and setting up Xcode project

.PHONY: all framework create-targets setup-project run

# Default target
all: check-deps framework setup-project

# Check dependencies
check-deps:
	@echo "🔍 Checking dependencies..."
	@command -v ruby >/dev/null 2>&1 || { echo "❌ Ruby not found. Install via: brew install ruby"; exit 1; }
	@command -v xcodebuild >/dev/null 2>&1 || { echo "❌ Xcode command line tools not found. Install via: xcode-select --install"; exit 1; }
	@echo "✅ All dependencies found"

# Build KMP framework for macOS
framework:
	@echo "🔨 Building SharedUI.framework for macOS..."
	@echo "This may take a few minutes..."
	@if [ ! -d "sharedUI/build" ]; then \
		echo "📂 Creating build directories..."; \
		mkdir -p sharedUI/build; \
	fi
	@echo "Building macOS frameworks using Gradle..."
	./gradlew :sharedUI:assembleSharedUIReleaseFrameworkMacosArm64 \
		:sharedUI:assembleSharedUIReleaseFrameworkMacosX64 -q || \
		{ echo "❌ Framework build failed"; exit 1; }
	@echo "✅ Frameworks built successfully"
	@echo "Framework location: sharedUI/build/bin/macosArm64/releaseFramework/SharedUI.framework"

# Setup Xcode project
setup-project: create-targets
	@echo "✅ Project setup complete"

# Create Xcode targets
create-targets:
	@echo "🎯 Setting up Xcode targets..."
	@echo "Note: This requires manual configuration in Xcode"
	@echo "Please follow the instructions in MACOS_IMPLEMENTATION.md"

# Convenience: Open Xcode project
open:
	@echo "📖 Opening Xcode project..."
	@open iosApp/iosApp.xcodeproj

# Convenience: Run the macOS app (after building in Xcode)
run:
	@echo "🏃 Running macOS App..."
	@echo "Open in Xcode first (Cmd+R)"

# Clean build
 clean:
	@echo "🧹 Cleaning..."
	@echo "Use Xcode's "Product→Clean Build Folder" (Shift+Cmd+K)"

# Help
help:
	@echo "Olcbox macOS VPN Client Build System"
	@echo "====================================="
	@echo ""
	@echo "Available targets:"
	@echo "  make all       - Build everything"
	@echo "  make framework - Build KMP framework only"
	@echo "  make open      - Open Xcode project"
	@echo "  make clean     - Clean build (use in Xcode)"
	@echo "  make help      - Show this help"
	@echo ""
	@echo "Quick start:"
	@echo "  1. make framework"
	@echo "  2. make open"
	@echo "  3. Follow setup in MACOS_IMPLEMENTATION.md"
	@echo ""
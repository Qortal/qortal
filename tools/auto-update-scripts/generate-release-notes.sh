#!/bin/bash
# Check if version argument is passed
if [ -z "$1" ]; then
    echo "Usage: $0 <VERSION>"
    exit 1
fi
VERSION="$1"
# Repository and branch information
REPO="Qortal/qortal"
BRANCH="master"
WORKING_QORTAL_DIR='./qortal'

# 1. Check if working directory exists
if [ ! -d "$WORKING_QORTAL_DIR" ]; then
    echo "Error: Working directory '$WORKING_QORTAL_DIR' not found."
    read -p "Would you like to: (1) Create a new directory here, or (2) Specify a full path? [1/2]: " choice
    if [ "$choice" = "1" ]; then
        mkdir -p "$WORKING_QORTAL_DIR"
        echo "Created new directory: $WORKING_QORTAL_DIR"
    elif [ "$choice" = "2" ]; then
        read -p "Enter full path to working directory: " new_path
        WORKING_QORTAL_DIR="$new_path"
        echo "Using specified directory: $WORKING_QORTAL_DIR"
    else
        echo "Invalid choice. Exiting."
        exit 1
    fi
fi

# 2. Check for qortal.jar
JAR_FILE="$WORKING_QORTAL_DIR/qortal.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: $JAR_FILE not found."
    read -p "Would you like to: (1) Compile from source, (2) Use running qortal.jar, or (3) Specify a path? [1/2/3]: " choice
    if [ "$choice" = "1" ]; then
        echo "Cloning repo and compiling..."
        git clone https://github.com/Qortal/qortal.git /tmp/qortal
        if ! command -v mvn &> /dev/null; then
            echo "Error: Maven not found. Please install Maven and try again."
            exit 1
        fi
        cd /tmp/qortal || exit
        mvn clean package
        cp target/qortal-*.jar "$WORKING_QORTAL_DIR/qortal.jar"
        cd - || exit
    elif [ "$choice" = "2" ]; then
        if [ -f "$HOME/qortal/qortal.jar" ]; then
            cp "$HOME/qortal/qortal.jar" "$WORKING_QORTAL_DIR/"
            echo "Copied from $HOME/qortal/qortal.jar"
        else
            echo "Error: $HOME/qortal/qortal.jar not found."
            exit 1
        fi
    elif [ "$choice" = "3" ]; then
        read -p "Enter full path to qortal.jar: " jar_path
        cp "$jar_path" "$WORKING_QORTAL_DIR/"
        echo "Used specified path: $jar_path"
    else
        echo "Invalid choice. Exiting."
        exit 1
    fi
fi

# 3. Check for required files (settings.json, log4j2.properties, etc.)
REQUIRED_FILES=("settings.json" "log4j2.properties" "start.sh" "stop.sh" "qort")
for file in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$WORKING_QORTAL_DIR/$file" ]; then
        echo "Error: $WORKING_QORTAL_DIR/$file not found."
        read -p "Would you like to: (1) Get files from GitHub (2) exit and copy files manually then re-run? [1/2]: " choice
        if [ "$choice" = "1" ]; then
            if [ "$file" = "settings.json" ]; then
                cat <<EOF > "$WORKING_QORTAL_DIR/settings.json"
{
    "balanceRecorderEnabled": true,
    "apiWhitelistEnabled": false,
    "allowConnectionsWithOlderPeerVersions": false,
    "apiRestricted": false
}
EOF
            elif [ "${file}" = "qort" ]; then
                echo "Downloading from GitHub..."
                curl -s "https://raw.githubusercontent.com/Qortal/qortal/refs/heads/$BRANCH/tools/$file" -o "$WORKING_QORTAL_DIR/$file"
                echo "Making $file script executable..."
                chmod +x "$WORKING_QORTAL_DIR/$file"
            elif [ "${file}" = "start.sh" ]; then
                echo "Downloading from GitHub..."
                curl -s "https://raw.githubusercontent.com/Qortal/qortal/refs/heads/$BRANCH/$file" -o "$WORKING_QORTAL_DIR/$file"
                echo "Making $file script executable..."
                chmod +x "$WORKING_QORTAL_DIR/$file"
            elif [ "${file}" = "stop.sh" ]; then
                echo "Downloading from GitHub..."
                curl -s "https://raw.githubusercontent.com/Qortal/qortal/refs/heads/$BRANCH/$file" -o "$WORKING_QORTAL_DIR/$file"
                echo "Making $file script executable..."
                chmod +x "$WORKING_QORTAL_DIR/$file"
            else
                echo "Downloading from GitHub..."
                curl -s "https://raw.githubusercontent.com/Qortal/qortal/refs/heads/$BRANCH/$file" -o "$WORKING_QORTAL_DIR/$file"
            fi
        elif [ "$choice" = "2" ]; then
            echo "copy files manually to this location then re-run script..."
            sleep 5 
            exit 1
        else
            echo "Invalid choice. Exiting."
            exit 1
        fi
    fi
done

# Continue with the rest of the script...
# (The rest of the script remains unchanged)

# Fetch the latest 100 commits
COMMITS_JSON=$(curl -s "https://api.github.com/repos/${REPO}/commits?sha=${BRANCH}&per_page=100")

# Extract bump version commits
BUMP_COMMITS=$(echo "$COMMITS_JSON" | jq -r '.[] | select(.commit.message | test("bump version to"; "i")) | .sha')

CURRENT_BUMP_COMMIT=$(echo "$COMMITS_JSON" | jq -r ".[] | select(.commit.message | test(\"bump version to ${VERSION}\"; \"i\")) | .sha" | head -n1)
PREV_BUMP_COMMIT=$(echo "$BUMP_COMMITS" | sed -n '2p')

if [ -z "$CURRENT_BUMP_COMMIT" ]; then
    echo "Error: Could not find bump commit for version ${VERSION} in ${REPO}/${BRANCH}"
    exit 1
fi

# Get changelog between previous and current commit
echo "Generating changelog between ${PREV_BUMP_COMMIT} and ${CURRENT_BUMP_COMMIT}..."
CHANGELOG=$(curl -s "https://api.github.com/repos/${REPO}/compare/${PREV_BUMP_COMMIT}...${CURRENT_BUMP_COMMIT}" | jq -r '.commits[] | .sha[0:7] as $sha | (.commit.message | split("\n")) as $lines | ($lines[0]) as $title | ($lines[1:] | map(select(length > 0))) as $body | "- " + $title + "\n  - " + $sha + (if ($body | length) > 0 then "\n  " + ($body | join("\n  ")) else "" end)')

# Fetch latest commit timestamp from GitHub API for final file timestamping
COMMIT_API_URL="https://api.github.com/repos/${REPO}/commits?sha=${BRANCH}&per_page=1"
COMMIT_TIMESTAMP=$(curl -s "${COMMIT_API_URL}" | jq -r '.[0].commit.committer.date')

if [ -z "${COMMIT_TIMESTAMP}" ] || [ "${COMMIT_TIMESTAMP}" == "null" ]; then
    echo "Error: Unable to retrieve the latest commit timestamp from GitHub API."
    exit 1
fi

# Define file names
JAR_FILE="qortal/qortal.jar"
EXE_FILE="qortal.exe"
ZIP_FILE="qortal.zip"

calculate_hashes() {
    local file="$1"
    echo "Calculating hashes for ${file}..."
    MD5=$(md5sum "${file}" | awk '{print $1}')
    SHA1=$(sha1sum "${file}" | awk '{print $1}')
    SHA256=$(sha256sum "${file}" | awk '{print $1}')
    echo "MD5: ${MD5}, SHA1: ${SHA1}, SHA256: ${SHA256}"
}

# Hashes for qortal.jar
if [ -f "${JAR_FILE}" ]; then
    calculate_hashes "${JAR_FILE}"
    JAR_MD5=${MD5}
    JAR_SHA1=${SHA1}
    JAR_SHA256=${SHA256}
else
    echo "Error: ${JAR_FILE} not found."
    exit 1
fi

# Hashes for qortal.exe
if [ -f "${EXE_FILE}" ]; then
    calculate_hashes "${EXE_FILE}"
    EXE_MD5=${MD5}
    EXE_SHA1=${SHA1}
    EXE_SHA256=${SHA256}
else
    echo "Warning: ${EXE_FILE} not found. Skipping."
    EXE_MD5="<INPUT>"
    EXE_SHA1="<INPUT>"
    EXE_SHA256="<INPUT>"
fi

# Apply commit timestamp to files in qortal/
echo "Applying commit timestamp (${COMMIT_TIMESTAMP}) to files..."
mv qortal.exe ${WORKING_QORTAL_DIR} 2>/dev/null || true
find ${WORKING_QORTAL_DIR} -type f -exec touch -d "${COMMIT_TIMESTAMP}" {} \;
mv ${WORKING_QORTAL_DIR}/qortal.exe . 2>/dev/null || true

# Create qortal.zip
echo "Packing ${ZIP_FILE}..."
7z a -r -tzip "${ZIP_FILE}" ${WORKING_QORTAL_DIR}/ -stl
if [ $? -ne 0 ]; then
    echo "Error: Failed to create ${ZIP_FILE}."
    exit 1
fi

calculate_hashes "${ZIP_FILE}"
ZIP_MD5=${MD5}
ZIP_SHA1=${SHA1}
ZIP_SHA256=${SHA256}

# Generate release notes
cat <<EOF > release-notes.txt
### **_Qortal Core V${VERSION}_**

#### 🔄 Changes Included in This Release:

${CHANGELOG}

### [qortal.jar](https://github.com/Qortal/qortal/releases/download/v${VERSION}/qortal.jar)

\`MD5: ${JAR_MD5}\`  qortal.jar  
\`SHA1: ${JAR_SHA1}\`  qortal.jar  
\`SHA256: ${JAR_SHA256}\`  qortal.jar  

### [qortal.exe](https://github.com/Qortal/qortal/releases/download/v${VERSION}/qortal.exe)

\`MD5: ${EXE_MD5}\`  qortal.exe  
\`SHA1: ${EXE_SHA1}\`  qortal.exe  
\`SHA256: ${EXE_SHA256}\`  qortal.exe  

[VirusTotal report for qortal.exe](https://www.virustotal.com/gui/file/${EXE_SHA256}/detection)

### [qortal.zip](https://github.com/Qortal/qortal/releases/download/v${VERSION}/qortal.zip)

Contains bare minimum of:
* built \`qortal.jar\`  
* \`log4j2.properties\` from git repo  
* \`start.sh\` from git repo  
* \`stop.sh\` from git repo  
* \`qort\` script for linux/mac easy API utilization  
* \`printf "{\n}\n" > settings.json\`

All timestamps set to same date-time as commit.  
Packed with \`7z a -r -tzip qortal.zip qortal/\`

\`MD5: ${ZIP_MD5}\`  qortal.zip  
\`SHA1: ${ZIP_SHA1}\`  qortal.zip  
\`SHA256: ${ZIP_SHA256}\`  qortal.zip  
EOF

echo "Release notes generated: release-notes.txt"

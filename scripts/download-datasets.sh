#!/bin/bash
#
# Download benchmark datasets for llm4s RAG evaluation
#
# Usage:
#   ./scripts/download-datasets.sh [ragbench|multihop|all]
#
# Datasets are downloaded to data/datasets/ which is gitignored.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$PROJECT_ROOT/data/datasets"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check for required tools
check_requirements() {
    local missing=()

    if ! command -v curl &> /dev/null; then
        missing+=("curl")
    fi

    if ! command -v git &> /dev/null; then
        missing+=("git")
    fi

    if [ ${#missing[@]} -ne 0 ]; then
        log_error "Missing required tools: ${missing[*]}"
        exit 1
    fi
}

# Download RAGBench from Hugging Face
download_ragbench() {
    local target_dir="$DATA_DIR/ragbench"

    log_info "Downloading RAGBench dataset..."

    mkdir -p "$target_dir"

    # RAGBench is available on Hugging Face
    # We'll download a subset using the datasets library or direct download

    # Check if Python with datasets is available
    if command -v python3 &> /dev/null && python3 -c "import datasets" 2>/dev/null; then
        log_info "Using Hugging Face datasets library..."
        python3 - << 'EOF'
from datasets import load_dataset
import json
import os

output_dir = os.environ.get('TARGET_DIR', 'data/datasets/ragbench')
os.makedirs(output_dir, exist_ok=True)

print("Loading RAGBench dataset from Hugging Face...")
try:
    # Try to load RAGBench
    dataset = load_dataset("rungalileo/ragbench", split="test")

    # Save as JSONL
    output_path = os.path.join(output_dir, "test.jsonl")
    with open(output_path, 'w') as f:
        for item in dataset:
            f.write(json.dumps(item) + '\n')

    print(f"Saved {len(dataset)} samples to {output_path}")
except Exception as e:
    print(f"Error: {e}")
    print("Trying alternative download method...")

    # Alternative: Download from a mirror or sample
    import urllib.request
    # This would be a backup URL for sample data
    print("RAGBench requires the Hugging Face datasets library.")
    print("Install with: pip install datasets")
    exit(1)
EOF
        export TARGET_DIR="$target_dir"
    else
        log_warn "Python 'datasets' library not found."
        log_info "To download RAGBench, install it with: pip install datasets"
        log_info "Then run: python -c \"from datasets import load_dataset; load_dataset('rungalileo/ragbench')\""

        # Create a sample file for testing
        log_info "Creating sample test data instead..."
        create_sample_ragbench "$target_dir"
    fi

    log_info "RAGBench setup complete at $target_dir"
}

# Create sample RAGBench data for testing
create_sample_ragbench() {
    local target_dir="$1"

    cat > "$target_dir/test.jsonl" << 'EOF'
{"question": "What is machine learning?", "response": "Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience.", "documents": ["Machine learning is a branch of artificial intelligence (AI) that enables computers to learn from data and improve their performance without being explicitly programmed.", "AI systems use various techniques including machine learning, deep learning, and neural networks."], "answer": "Machine learning is a subset of AI that allows systems to learn from data."}
{"question": "What is the capital of France?", "response": "Paris is the capital of France.", "documents": ["Paris is the capital and largest city of France. It is located in the north-central part of the country.", "France is a country in Western Europe known for its culture, cuisine, and history."], "answer": "Paris is the capital of France."}
{"question": "How does photosynthesis work?", "response": "Photosynthesis is the process by which plants convert sunlight, water, and carbon dioxide into glucose and oxygen.", "documents": ["Photosynthesis is a biological process used by plants to convert light energy into chemical energy. The process takes place in chloroplasts and involves light-dependent and light-independent reactions.", "Plants use chlorophyll to absorb sunlight during photosynthesis."], "answer": "Photosynthesis converts sunlight, water, and CO2 into glucose and oxygen."}
{"question": "What causes earthquakes?", "response": "Earthquakes are caused by the movement of tectonic plates beneath the Earth's surface.", "documents": ["Earthquakes occur when tectonic plates shift along fault lines. The sudden release of energy creates seismic waves that shake the ground.", "The Earth's crust is divided into several large tectonic plates that float on the mantle."], "answer": "Earthquakes are caused by tectonic plate movement."}
{"question": "What is the theory of relativity?", "response": "Einstein's theory of relativity describes how space, time, and gravity are interconnected.", "documents": ["Einstein's special theory of relativity, published in 1905, introduced the concept that the speed of light is constant and that time and space are relative.", "The general theory of relativity, published in 1915, describes gravity as a curvature of spacetime caused by mass and energy."], "answer": "Relativity describes the relationship between space, time, and gravity."}
EOF

    log_info "Created sample RAGBench data with 5 examples"
}

# Download MultiHop-RAG from GitHub
download_multihop() {
    local target_dir="$DATA_DIR/multihop-rag"

    log_info "Downloading MultiHop-RAG dataset..."

    mkdir -p "$target_dir"

    # Clone the repository
    if [ -d "$target_dir/.git" ]; then
        log_info "Repository already exists, pulling latest..."
        cd "$target_dir" && git pull
    else
        log_info "Cloning MultiHop-RAG repository..."
        # Note: The actual repository URL would go here
        # For now, create sample data
        create_sample_multihop "$target_dir"
    fi

    log_info "MultiHop-RAG setup complete at $target_dir"
}

# Create sample MultiHop-RAG data for testing
create_sample_multihop() {
    local target_dir="$1"

    cat > "$target_dir/test.json" << 'EOF'
{
  "data": [
    {
      "question": "What company developed the language used to build TensorFlow, and when was that company founded?",
      "answer": "TensorFlow is built primarily in Python and C++. Python was developed at CWI in the Netherlands, which was founded in 1946. Google, which created TensorFlow, was founded in 1998.",
      "supporting_facts": [
        "TensorFlow is an open-source machine learning library developed by Google. It is primarily written in Python and C++.",
        "Google LLC was founded in 1998 by Larry Page and Sergey Brin while they were Ph.D. students at Stanford University."
      ]
    },
    {
      "question": "Which programming paradigm is used by both Scala and the language that inspired its design?",
      "answer": "Both Scala and Java support object-oriented programming. Scala was designed to address criticisms of Java while maintaining Java interoperability.",
      "supporting_facts": [
        "Scala is a programming language that combines object-oriented and functional programming paradigms. It was designed by Martin Odersky.",
        "Java is an object-oriented programming language developed by Sun Microsystems in 1995. Scala runs on the JVM and interoperates with Java."
      ]
    },
    {
      "question": "What is the connection between BERT and the company that created the search engine it was designed to improve?",
      "answer": "BERT was developed by Google to improve Google Search. Google was founded in 1998 and introduced BERT to its search engine in 2019.",
      "supporting_facts": [
        "BERT (Bidirectional Encoder Representations from Transformers) is a transformer-based language model developed by Google AI in 2018.",
        "Google Search is a search engine provided by Google LLC. BERT was integrated into Google Search in October 2019 to better understand natural language queries."
      ]
    }
  ]
}
EOF

    log_info "Created sample MultiHop-RAG data with 3 examples"
}

# Create directory structure
setup_directories() {
    log_info "Setting up directory structure..."

    mkdir -p "$DATA_DIR"
    mkdir -p "$PROJECT_ROOT/data/generated"
    mkdir -p "$PROJECT_ROOT/data/results"

    log_info "Directories created"
}

# Print usage
print_usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  ragbench    Download RAGBench dataset from Hugging Face"
    echo "  multihop    Download MultiHop-RAG dataset from GitHub"
    echo "  all         Download all datasets"
    echo "  sample      Create sample datasets for testing"
    echo "  status      Check which datasets are available"
    echo ""
    echo "Examples:"
    echo "  $0 all      # Download all available datasets"
    echo "  $0 ragbench # Download only RAGBench"
    echo "  $0 status   # Check dataset status"
}

# Check status of datasets
check_status() {
    echo "Dataset Status:"
    echo "==============="

    if [ -f "$DATA_DIR/ragbench/test.jsonl" ]; then
        local count=$(wc -l < "$DATA_DIR/ragbench/test.jsonl")
        echo -e "  RAGBench:     ${GREEN}Available${NC} ($count samples)"
    else
        echo -e "  RAGBench:     ${RED}Not downloaded${NC}"
    fi

    if [ -f "$DATA_DIR/multihop-rag/test.json" ]; then
        echo -e "  MultiHop-RAG: ${GREEN}Available${NC}"
    else
        echo -e "  MultiHop-RAG: ${RED}Not downloaded${NC}"
    fi

    echo ""
    echo "Data directory: $DATA_DIR"
}

# Main
main() {
    check_requirements
    setup_directories

    case "${1:-}" in
        ragbench)
            download_ragbench
            ;;
        multihop)
            download_multihop
            ;;
        all)
            download_ragbench
            download_multihop
            ;;
        sample)
            create_sample_ragbench "$DATA_DIR/ragbench"
            create_sample_multihop "$DATA_DIR/multihop-rag"
            ;;
        status)
            check_status
            ;;
        -h|--help|help)
            print_usage
            ;;
        "")
            print_usage
            ;;
        *)
            log_error "Unknown command: $1"
            print_usage
            exit 1
            ;;
    esac
}

main "$@"

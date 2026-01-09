#!/bin/bash
#
# Pioneer WYT Mini-Split Test Script
# Tests the bridge API and optionally Hubitat Maker API integration
#

set -e

# Load environment
if [ -f ../.env ]; then
    source ../.env
elif [ -f .env ]; then
    source .env
fi

# Configuration
BRIDGE_URL="${BRIDGE_URL:-http://localhost:8000}"
BRIDGE_TOKEN="${BRIDGE_TOKEN:-changeme}"
HUBITAT_IP="${HUBITAT_IP:-}"
HUBITAT_MAKER_API_ID="${HUBITAT_MAKER_API_ID:-}"
HUBITAT_MAKER_API_TOKEN="${HUBITAT_MAKER_API_TOKEN:-}"
HUBITAT_DEVICE_ID="${HUBITAT_DEVICE_ID:-}"  # Device ID in Hubitat

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Bridge API tests
test_bridge_health() {
    log_info "Testing bridge health endpoint..."
    response=$(curl -s "${BRIDGE_URL}/health")
    if echo "$response" | grep -q '"status":"ok"'; then
        log_info "Health check passed: $response"
        return 0
    else
        log_error "Health check failed: $response"
        return 1
    fi
}

test_bridge_status() {
    log_info "Testing bridge status endpoint..."
    response=$(curl -s -H "Authorization: Bearer ${BRIDGE_TOKEN}" "${BRIDGE_URL}/status")
    if echo "$response" | grep -q '"online"'; then
        log_info "Status check passed"
        echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
        return 0
    else
        log_error "Status check failed: $response"
        return 1
    fi
}

test_bridge_power() {
    local state=$1
    log_info "Testing power ${state}..."
    response=$(curl -s -X POST \
        -H "Authorization: Bearer ${BRIDGE_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"command\": \"power\", \"value\": ${state}}" \
        "${BRIDGE_URL}/command")

    if echo "$response" | grep -q '"success":true'; then
        log_info "Power ${state} command sent successfully"
        return 0
    else
        log_error "Power command failed: $response"
        return 1
    fi
}

test_bridge_mode() {
    local mode=$1
    log_info "Testing mode: ${mode}..."
    response=$(curl -s -X POST \
        -H "Authorization: Bearer ${BRIDGE_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"command\": \"mode\", \"value\": \"${mode}\"}" \
        "${BRIDGE_URL}/command")

    if echo "$response" | grep -q '"success":true'; then
        log_info "Mode ${mode} command sent successfully"
        return 0
    else
        log_error "Mode command failed: $response"
        return 1
    fi
}

test_bridge_temp() {
    local temp=$1
    log_info "Testing temperature: ${temp}..."
    response=$(curl -s -X POST \
        -H "Authorization: Bearer ${BRIDGE_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"command\": \"target_temp\", \"value\": ${temp}}" \
        "${BRIDGE_URL}/command")

    if echo "$response" | grep -q '"success":true'; then
        log_info "Temperature ${temp} command sent successfully"
        return 0
    else
        log_error "Temperature command failed: $response"
        return 1
    fi
}

test_bridge_fan() {
    local fan=$1
    log_info "Testing fan: ${fan}..."
    response=$(curl -s -X POST \
        -H "Authorization: Bearer ${BRIDGE_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"command\": \"fan\", \"value\": \"${fan}\"}" \
        "${BRIDGE_URL}/command")

    if echo "$response" | grep -q '"success":true'; then
        log_info "Fan ${fan} command sent successfully"
        return 0
    else
        log_error "Fan command failed: $response"
        return 1
    fi
}

# Hubitat Maker API tests
test_hubitat_status() {
    if [ -z "$HUBITAT_DEVICE_ID" ]; then
        log_warn "HUBITAT_DEVICE_ID not set, skipping Hubitat tests"
        return 0
    fi

    log_info "Testing Hubitat device status..."
    url="http://${HUBITAT_IP}/apps/api/${HUBITAT_MAKER_API_ID}/devices/${HUBITAT_DEVICE_ID}?access_token=${HUBITAT_MAKER_API_TOKEN}"
    response=$(curl -s "$url")

    if echo "$response" | grep -q '"name"'; then
        log_info "Hubitat device status retrieved"
        echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
        return 0
    else
        log_error "Hubitat status check failed: $response"
        return 1
    fi
}

test_hubitat_command() {
    local command=$1
    local value=$2

    if [ -z "$HUBITAT_DEVICE_ID" ]; then
        log_warn "HUBITAT_DEVICE_ID not set, skipping"
        return 0
    fi

    log_info "Testing Hubitat command: ${command} ${value}..."
    if [ -z "$value" ]; then
        url="http://${HUBITAT_IP}/apps/api/${HUBITAT_MAKER_API_ID}/devices/${HUBITAT_DEVICE_ID}/${command}?access_token=${HUBITAT_MAKER_API_TOKEN}"
    else
        url="http://${HUBITAT_IP}/apps/api/${HUBITAT_MAKER_API_ID}/devices/${HUBITAT_DEVICE_ID}/${command}/${value}?access_token=${HUBITAT_MAKER_API_TOKEN}"
    fi

    response=$(curl -s "$url")
    log_info "Response: $response"
}

# Main test runner
run_bridge_tests() {
    echo ""
    echo "========================================"
    echo "Bridge API Tests"
    echo "========================================"

    test_bridge_health
    sleep 1

    test_bridge_status
    sleep 1

    read -p "Run power tests? (y/n): " confirm
    if [ "$confirm" = "y" ]; then
        test_bridge_power true
        sleep 3
        read -p "Did the unit turn ON? (y/n): " result
        [ "$result" = "y" ] && log_info "PASS" || log_warn "FAIL - check DPID"

        test_bridge_power false
        sleep 3
        read -p "Did the unit turn OFF? (y/n): " result
        [ "$result" = "y" ] && log_info "PASS" || log_warn "FAIL - check DPID"
    fi

    read -p "Run mode tests? (y/n): " confirm
    if [ "$confirm" = "y" ]; then
        test_bridge_power true
        sleep 2

        for mode in cold hot auto wind; do
            test_bridge_mode $mode
            sleep 2
            read -p "Did mode change to ${mode}? (y/n/s=skip): " result
            [ "$result" = "y" ] && log_info "PASS: $mode" || [ "$result" = "n" ] && log_warn "FAIL: $mode"
        done
    fi

    read -p "Run temperature test? (y/n): " confirm
    if [ "$confirm" = "y" ]; then
        test_bridge_power true
        sleep 2
        test_bridge_mode cold
        sleep 2

        test_bridge_temp 72
        sleep 2
        read -p "Did display show 72F? (y/n): " result
        [ "$result" = "y" ] && log_info "PASS" || log_warn "FAIL - check temp scaling"
    fi

    read -p "Run fan tests? (y/n): " confirm
    if [ "$confirm" = "y" ]; then
        for fan in auto low medium high; do
            test_bridge_fan $fan
            sleep 2
            read -p "Did fan change to ${fan}? (y/n/s=skip): " result
            [ "$result" = "y" ] && log_info "PASS: $fan" || [ "$result" = "n" ] && log_warn "FAIL: $fan"
        done
    fi
}

run_hubitat_tests() {
    if [ -z "$HUBITAT_DEVICE_ID" ]; then
        log_warn "Hubitat tests require HUBITAT_DEVICE_ID to be set"
        return
    fi

    echo ""
    echo "========================================"
    echo "Hubitat Maker API Tests"
    echo "========================================"

    test_hubitat_status
    sleep 1

    read -p "Run Hubitat command tests? (y/n): " confirm
    if [ "$confirm" = "y" ]; then
        test_hubitat_command "on"
        sleep 3
        read -p "Did unit turn on via Hubitat? (y/n): " result

        test_hubitat_command "setCoolingSetpoint" "74"
        sleep 2
        read -p "Did temp change to 74F? (y/n): " result

        test_hubitat_command "setThermostatMode" "cool"
        sleep 2
        read -p "Did mode change to cool? (y/n): " result

        test_hubitat_command "off"
        sleep 2
    fi
}

# Main
main() {
    echo "Pioneer WYT Mini-Split Test Suite"
    echo "=================================="
    echo ""
    echo "Bridge URL: ${BRIDGE_URL}"
    echo "Hubitat IP: ${HUBITAT_IP:-not configured}"
    echo ""

    PS3="Select test suite: "
    options=("Bridge API Tests" "Hubitat Tests" "All Tests" "Quick Status Check" "Quit")
    select opt in "${options[@]}"; do
        case $opt in
            "Bridge API Tests")
                run_bridge_tests
                ;;
            "Hubitat Tests")
                run_hubitat_tests
                ;;
            "All Tests")
                run_bridge_tests
                run_hubitat_tests
                ;;
            "Quick Status Check")
                test_bridge_health
                test_bridge_status
                ;;
            "Quit")
                break
                ;;
            *)
                echo "Invalid option"
                ;;
        esac
        break
    done
}

main "$@"

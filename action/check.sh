#!/bin/sh
# okf-cli GitHub Action (action.yml; docs/github-actions.md): runs `okf check` and `okf impact` on the repository
# `OKF_INPUT_REPO` through this action's own copy of the launcher, launcher/okfw, which runs the version the
# repository's pin names (OKFW_PIN) from a cache in the runner's temporary directory (OKFW_CACHE_DIR).
#
# Environment (action.yml sets every variable): the inputs as OKF_INPUT_REPO, OKF_INPUT_STRICT, OKF_INPUT_REFS_STRICT,
# OKF_INPUT_CODE_PATHS_REQUIRED, OKF_INPUT_IMPACT, OKF_INPUT_BASE, OKF_INPUT_IMPACT_STRICT and
# OKF_INPUT_VERIFY_PROVENANCE; the event facts that action/impact-args.sh reads; OKFW_PIN, OKFW_CACHE_DIR,
# GITHUB_ACTION_PATH and GITHUB_OUTPUT.
#
# Exit code: 2 for an invalid input (an empty repo, a repo or base with a control character, a flag that is not true or
# false) or a shallow clone with impact on (nothing runs), the code of `okfw --verify` when it fails, else the first
# non-zero code of check and impact. The action's own lines go to standard error and start with `okf-cli action: `;
# the jar's output is passed on unchanged.

main() {
  [ -n "${OKF_INPUT_REPO-}" ] || okf_error "input 'repo' must not be empty"
  okf_check_line repo "$OKF_INPUT_REPO"
  okf_check_bool strict "${OKF_INPUT_STRICT-}"
  okf_check_bool refs-strict "${OKF_INPUT_REFS_STRICT-}"
  okf_check_bool code-paths-required "${OKF_INPUT_CODE_PATHS_REQUIRED-}"
  okf_check_bool impact "${OKF_INPUT_IMPACT-}"
  case ${OKF_INPUT_IMPACT_STRICT-} in
    auto | true | false) ;;
    *) okf_error "input 'impact-strict' must be auto, true or false" ;;
  esac
  okf_check_bool verify-provenance "${OKF_INPUT_VERIFY_PROVENANCE-}"
  okf_check_line base "${OKF_INPUT_BASE-}"

  okf_repo=${OKF_INPUT_REPO-}
  okf_okfw=$GITHUB_ACTION_PATH/launcher/okfw

  # okf impact on a shallow clone would compare against a history that is not there.
  if [ "$OKF_INPUT_IMPACT" = true ] \
    && [ "$(git -C "$okf_repo" rev-parse --is-shallow-repository 2> /dev/null)" = true ]; then
    okf_error 'okf impact needs the full history: check out with fetch-depth: 0 (or set impact: false)'
  fi

  if [ "$OKF_INPUT_VERIFY_PROVENANCE" = true ]; then
    okf_say 'okfw --verify'
    okf_status=0
    sh "$okf_okfw" --verify || okf_status=$?
    if [ "$okf_status" -ne 0 ]; then
      exit "$okf_status"
    fi
  fi

  set -- check "--repo=$okf_repo" "--strict=$OKF_INPUT_STRICT" "--refs-strict=$OKF_INPUT_REFS_STRICT" \
    "--code-paths-required=$OKF_INPUT_CODE_PATHS_REQUIRED"
  okf_say "okf $*"
  okf_check_status=0
  sh "$okf_okfw" "$@" || okf_check_status=$?

  # Exit 2 (a usage, --repo or okf.yaml error, or the launcher's pin error) or 3 (no verified jar or Java): impact
  # would fail the same way.
  okf_impact_status=0
  if [ "$OKF_INPUT_IMPACT" = true ] && [ "$okf_check_status" -ne 2 ] && [ "$okf_check_status" -ne 3 ]; then
    # Sets okf_impact_base_set, okf_impact_base and okf_impact_strict; each value is one argument (never through eval).
    # shellcheck source=action/impact-args.sh
    . "$GITHUB_ACTION_PATH/action/impact-args.sh"
    set -- impact "--repo=$okf_repo"
    if [ -n "$okf_impact_base_set" ]; then
      set -- "$@" "--base=$okf_impact_base"
    fi
    set -- "$@" "--impact-strict=$okf_impact_strict"
    okf_say "okf $*"
    sh "$okf_okfw" "$@" || okf_impact_status=$?
  fi

  okf_version_output

  if [ "$okf_check_status" -ne 0 ]; then
    exit "$okf_check_status"
  fi
  exit "$okf_impact_status"
}

# Prints the ::error annotation for the message $1 and exits 2.
okf_error() {
  printf '::error title=okf-cli::%s\n' "$1"
  exit 2
}

# Exits 2 unless the value $2 of the input $1 is true or false.
okf_check_bool() {
  case $2 in
    true | false) ;;
    *) okf_error "input '$1' must be true or false" ;;
  esac
}

# Exits 2 unless the value $2 of the input $1 is one line without control characters (U+0000 to U+001F, U+007F): a
# value is one argument of okf-cli and is echoed in the log.
okf_check_line() {
  if [ "$(printf '%sx' "$2" | LC_ALL=C tr -d '\000-\037\177')" != "${2}x" ]; then
    okf_error "input '$1' must be one line without control characters"
  fi
}

# Prints "okf-cli action: <message>" on standard error.
okf_say() {
  printf 'okf-cli action: %s\n' "$1" >&2
}

# Writes the output `version`: the pinned version as `okf version` prints it (`okf-cli <version>`), when it is a
# version such as release.yml accepts. Its standard error is dropped: a launcher error was already shown by check.
okf_version_output() {
  okf_out=$(sh "$okf_okfw" version 2> /dev/null) || return 0
  # The CR of a CRLF line end (Java on Windows); all trailing ones, as the shell of Git for Windows already drops them.
  okf_cr=$(printf '\r')
  # The guard: with an empty okf_cr the pattern would match every value and never shorten it.
  while [ -n "$okf_cr" ]; do
    case $okf_out in
      *"$okf_cr") okf_out=${okf_out%"$okf_cr"} ;;
      *) break ;;
    esac
  done
  case $okf_out in
    'okf-cli '*) okf_version=${okf_out#okf-cli } ;;
    *) return 0 ;;
  esac
  # Only [0-9A-Za-z.-] reaches grep, which matches line by line: a newline or any other character fails before it.
  case $okf_version in
    '' | *[!0-9A-Za-z.-]*) return 0 ;;
  esac
  printf '%s\n' "$okf_version" \
    | LC_ALL=C grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?$' \
    || return 0
  if [ -n "${GITHUB_OUTPUT-}" ]; then
    printf 'version=%s\n' "$okf_version" >> "$GITHUB_OUTPUT"
  fi
}

main "$@"

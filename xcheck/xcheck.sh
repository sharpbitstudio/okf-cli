#!/bin/sh
# okf-cli xcheck action (xcheck/action.yml; docs/github-actions.md): clones the satellites the input lists into the
# workspace, then runs `okf xcheck` on the hub through this action's own copy of the launcher, launcher/okfw, which
# runs the version the hub's pin names (OKFW_PIN = <hub>/okf-wrapper.properties) from a cache in the runner's
# temporary directory (OKFW_CACHE_DIR). It parses no okf.yaml: every check is the jar's.
#
# Environment (action.yml sets every variable): the inputs as OKF_INPUT_HUB, OKF_INPUT_SATELLITES, OKF_INPUT_TOKEN and
# OKF_INPUT_WORKSPACE; OKF_REPOSITORY_OWNER (the owner of a satellite named without one), OKF_DEFAULT_WORKSPACE (the
# workspace when the input is empty), OKF_REPORT (the report file), OKFW_CACHE_DIR, GITHUB_ACTION_PATH,
# GITHUB_SERVER_URL, GITHUB_OUTPUT and GITHUB_STEP_SUMMARY.
#
# The token: read once, removed from the environment, and given only to each `git clone`, through GIT_ASKPASS
# (xcheck/askpass.sh) and a variable set for that command alone; never in a URL, an argument or git's configuration.
# Every clone runs with the credential helpers reset (`-c credential.helper=`), so none receives the credential, and
# without prompts.
#
# Exit code: 2 for an invalid input (an empty hub, a hub or workspace with a control character, a satellite name that
# is not owner/repo or repo) or a failed clone, with one error annotation; else the code of `okfw xcheck`. Every name
# is checked before the first clone. The action's own lines go to standard error and start with
# `okf-cli xcheck action: `; git's, the launcher's and the jar's output is passed on unchanged.

main() {
  okf_token=${OKF_INPUT_TOKEN-}
  unset OKF_INPUT_TOKEN

  # A report left by an earlier run must not survive a run that writes none (an input or clone error, or the
  # launcher's exit 3).
  okf_report=${OKF_REPORT-}
  if [ -n "$okf_report" ]; then
    rm -f -- "$okf_report"
  fi

  [ -n "${OKF_INPUT_HUB-}" ] || okf_error "input 'hub' must not be empty"
  okf_check_line hub "$OKF_INPUT_HUB"
  okf_check_line workspace "${OKF_INPUT_WORKSPACE-}"
  okf_hub=$OKF_INPUT_HUB
  okf_workspace=${OKF_INPUT_WORKSPACE:-${OKF_DEFAULT_WORKSPACE-}}
  [ -n "$okf_workspace" ] || okf_error "input 'workspace' must not be empty"

  # One entry per whitespace-separated word, without globbing.
  set -f
  # shellcheck disable=SC2086
  set -- ${OKF_INPUT_SATELLITES-}
  set +f

  okf_n=0
  for okf_entry in "$@"; do
    okf_n=$((okf_n + 1))
    okf_split "$okf_entry" \
      || okf_error "input 'satellites': entry $okf_n is not owner/repo or repo (each name only letters, digits, '.', '_' and '-', and not '.' or '..')"
  done

  okf_server=${GITHUB_SERVER_URL:-https://github.com}
  for okf_entry in "$@"; do
    okf_split "$okf_entry"
    okf_dir=$okf_workspace/$okf_repo
    if [ -e "$okf_dir" ]; then
      okf_say "$okf_dir exists, not cloned"
      continue
    fi
    okf_url=$okf_server/$okf_owner/$okf_repo.git
    okf_say "git -c credential.helper= clone --no-tags -- $okf_url $okf_dir"
    okf_status=0
    OKF_XCHECK_TOKEN=$okf_token GIT_ASKPASS=$GITHUB_ACTION_PATH/askpass.sh GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never \
      GIT_LFS_SKIP_SMUDGE=1 git -c credential.helper= clone --no-tags -- "$okf_url" "$okf_dir" || okf_status=$?
    if [ "$okf_status" -ne 0 ]; then
      okf_error "cannot clone $okf_owner/$okf_repo: git clone exited with $okf_status"
    fi
  done

  set -- xcheck "--repo=$okf_hub" "--workspace=$okf_workspace" "--report=$okf_report"
  okf_say "okf $*"
  okf_status=0
  OKFW_PIN=$okf_hub/okf-wrapper.properties sh "$GITHUB_ACTION_PATH/../launcher/okfw" "$@" || okf_status=$?

  if [ -f "$okf_report" ] && [ -n "${GITHUB_STEP_SUMMARY-}" ]; then
    cat -- "$okf_report" >> "$GITHUB_STEP_SUMMARY"
  fi
  if [ -n "${GITHUB_OUTPUT-}" ]; then
    printf 'report=%s\nexit-code=%s\n' "$okf_report" "$okf_status" >> "$GITHUB_OUTPUT"
  fi
  exit "$okf_status"
}

# Splits the satellite entry $1 into okf_owner and okf_repo (`repo` alone: the owner is OKF_REPOSITORY_OWNER); fails
# unless both are names.
okf_split() {
  case $1 in
    */*/*) return 1 ;;
    */*)
      okf_owner=${1%%/*}
      okf_repo=${1#*/}
      ;;
    *)
      okf_owner=${OKF_REPOSITORY_OWNER-}
      okf_repo=$1
      ;;
  esac
  okf_name "$okf_owner" && okf_name "$okf_repo"
}

# Fails unless $1 is a name: one or more ASCII letters, digits, '.', '_' and '-', and not '.' or '..'. The letters
# are listed, not given as ranges, which a locale may widen.
okf_name() {
  case $1 in
    '' | . | .. | *[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-]*) return 1 ;;
  esac
}

# Prints the ::error annotation for the message $1 and exits 2.
okf_error() {
  printf '::error title=okf-cli::%s\n' "$1"
  exit 2
}

# Exits 2 unless the value $2 of the input $1 is one line without control characters (U+0000 to U+001F, U+007F): a
# value is one argument of git or okf-cli and is echoed in the log.
okf_check_line() {
  if [ "$(printf '%sx' "$2" | LC_ALL=C tr -d '\000-\037\177')" != "${2}x" ]; then
    okf_error "input '$1' must be one line without control characters"
  fi
}

# Prints "okf-cli xcheck action: <message>" on standard error.
okf_say() {
  printf 'okf-cli xcheck action: %s\n' "$1" >&2
}

main "$@"

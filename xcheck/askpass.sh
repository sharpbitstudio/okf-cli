#!/bin/sh
# okf-cli xcheck action (xcheck/action.yml; docs/github-actions.md): the GIT_ASKPASS helper of the satellite clones
# that xcheck/xcheck.sh runs. git runs it without a shell, with its prompt as the only argument, so it must stay
# executable (mode 100755 in the index).
#
# It answers git's username prompt (`Username for '<url>': `) with x-access-token and its password prompt
# (`Password for '<url>': `) with OKF_XCHECK_TOKEN, which xcheck.sh sets for the clone command only. Any other
# prompt, or no token, prints nothing and exits 1: git then fails instead of asking again.

main() {
  [ -n "${OKF_XCHECK_TOKEN-}" ] || exit 1
  case ${1-} in
    'Username for '*) printf '%s\n' x-access-token ;;
    'Password for '*) printf '%s\n' "$OKF_XCHECK_TOKEN" ;;
    *) exit 1 ;;
  esac
}

main "$@"

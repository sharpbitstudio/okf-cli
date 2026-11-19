#!/bin/sh
# okf-cli xcheck action (xcheck/action.yml; docs/github-actions.md): the credential helper of the satellite clones
# (`credential.<server>.helper` in xcheck/xcheck.sh). git runs it through its shell as `sh <path> <operation>`, with
# the request on standard input (gitcredentials(7)).
#
# For `get` with a non-empty `path=` line in its input (a request for a repository: the clones set
# credential.useHttpPath) it prints `username=x-access-token` and `password=<token>`, the token being OKF_XCHECK_TOKEN
# (set by xcheck.sh for the clone command only) up to its first CR or LF. Otherwise (`store`, `erase`, any other
# operation, no path, no or an empty token) it prints nothing. It always exits 0. It does not check the host: git calls
# it only for the server's scheme, host and port, for a proxy's credential request too, so a proxy anywhere else never
# gets the token. For a proxy at the server's own origin, git 2.47 and later pass no path (they reject a path in an
# http or https proxy URL); an older git passes the path of such a proxy URL, and the token goes to that origin, which
# gets it anyway.

main() {
  okf_path=
  while IFS= read -r okf_line && [ -n "$okf_line" ]; do
    case $okf_line in
      path=?*) okf_path=1 ;;
    esac
  done

  # Up to the first CR or LF, as git read an askpass answer.
  okf_newline='
'
  okf_cr=$(printf '\r')
  okf_token=${OKF_XCHECK_TOKEN-}
  okf_token=${okf_token%%"$okf_newline"*}
  okf_token=${okf_token%%"$okf_cr"*}
  if [ "${1-}" = get ] && [ -n "$okf_path" ] && [ -n "$okf_token" ]; then
    printf 'username=x-access-token\npassword=%s\n' "$okf_token"
  fi
  exit 0
}

main "$@"

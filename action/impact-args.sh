# shellcheck shell=sh
# shellcheck disable=SC2034 # the variables are check.sh's, which sources this file
# okf-cli GitHub Action: chooses the base and the strictness of `okf impact` from the event (docs/github-actions.md,
# "Base and strictness"). Sourced by action/check.sh (`. impact-args.sh`), never run on its own: it sets
#
#   okf_impact_base_set  1 when `--base` is passed, empty when okf impact's own default applies
#   okf_impact_base      the value of `--base` (any text: check.sh passes it as one argument)
#   okf_impact_strict    true or false, the value of `--impact-strict`
#
# A pure function of the variables below, which action.yml fills from the event: it reads nothing else, runs no other
# program and writes nothing.
#
#   OKF_EVENT                 github.event_name
#   OKF_REF                   github.ref
#   OKF_DEFAULT_BRANCH        github.event.repository.default_branch (empty: main)
#   OKF_BEFORE                github.event.before
#   OKF_MERGE_GROUP_BASE_SHA  github.event.merge_group.base_sha
#   OKF_PR_BASE_REF           github.base_ref
#   OKF_PR_AUTHOR             github.event.pull_request.user.login
#   OKF_ACTOR                 github.actor
#   OKF_INPUT_BASE            input `base`: `auto`, empty or blank for the table, anything else is the base
#   OKF_INPUT_IMPACT_STRICT   input `impact-strict`: `auto` for the table, else `true` or `false` (check.sh validates)

okf_impact_select() {
  okf_impact_default_branch=${OKF_DEFAULT_BRANCH:-main}
  # The table: base (okf_impact_base_set empty = not passed) and strictness per event. Dependabot is recognised by the
  # pull request's author or the actor, never by a branch name (a fork can name a branch dependabot/...).
  okf_impact_base=
  okf_impact_base_set=
  okf_impact_strict=false
  case ${OKF_EVENT-} in
    pull_request)
      okf_impact_base=origin/${OKF_PR_BASE_REF-}
      okf_impact_base_set=1
      okf_impact_strict_unless_dependabot "${OKF_PR_AUTHOR-}"
      ;;
    merge_group)
      # A merge queue that squashes drops the commit trailers; the pull request was checked strictly before.
      okf_impact_base=${OKF_MERGE_GROUP_BASE_SHA-}
      okf_impact_base_set=1
      ;;
    push | workflow_dispatch)
      case ${OKF_REF-} in
        "refs/heads/$okf_impact_default_branch")
          # A push to the default branch compares with the commit the branch moved from: all zeros for a new branch
          # (okf impact skips), unreachable after a force-push (okf impact warns and skips). A merge drops the commit
          # trailers, so findings are warnings. A manual run on the default branch falls under "anything else".
          if [ "$OKF_EVENT" = push ]; then
            okf_impact_base=${OKF_BEFORE-}
            okf_impact_base_set=1
          fi
          ;;
        refs/heads/gh-readonly-queue/*)
          # A push to a branch of GitHub's merge queue: a queue that squashes drops the commit trailers, as for
          # merge_group, so findings are warnings, with okf impact's own base.
          if [ "$OKF_EVENT" != push ]; then
            okf_impact_strict_unless_dependabot "${OKF_ACTOR-}"
          fi
          ;;
        refs/heads/*)
          # Another branch: okf impact's own default base (origin/<base_ref>, fallback <base_ref>).
          okf_impact_strict_unless_dependabot "${OKF_ACTOR-}"
          ;;
      esac
      ;;
  esac

  # Explicit inputs override the table, column by column. A base that is empty or only spaces counts as `auto`.
  okf_impact_blank=${OKF_INPUT_BASE-}
  while :; do
    case $okf_impact_blank in
      ' '*) okf_impact_blank=${okf_impact_blank# } ;;
      *) break ;;
    esac
  done
  case ${OKF_INPUT_BASE-} in
    auto) ;;
    *)
      if [ -n "$okf_impact_blank" ]; then
        okf_impact_base=$OKF_INPUT_BASE
        okf_impact_base_set=1
      fi
      ;;
  esac
  case ${OKF_INPUT_IMPACT_STRICT-} in
    true | false) okf_impact_strict=$OKF_INPUT_IMPACT_STRICT ;;
  esac
}

# Sets okf_impact_strict to false when the login $1 is Dependabot's (it cannot add a Docs-Impact: trailer), else to
# true.
okf_impact_strict_unless_dependabot() {
  if [ "$1" = 'dependabot[bot]' ]; then
    okf_impact_strict=false
  else
    okf_impact_strict=true
  fi
}

okf_impact_select

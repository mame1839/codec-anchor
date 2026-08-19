CA_MODULE_TAG_PREFIX=m
CA_APP_TAG_PREFIX=v
CA_UPDATE_BRANCH=module-update
CA_UPDATE_JSON=update.json
CA_UPDATE_CHANGELOG=changelog.md

ca_version_name() {
    _p=$1
    _n=${2:-}
    if [ -z "$_n" ]; then
        _n=$(git describe --tags --abbrev=0 --match "$_p[0-9]*" 2>/dev/null || true)
    fi
    _n=${_n#"$_p"}
    if printf '%s' "$_n" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        printf '%s\n' "$_n"
    else
        echo "$_p<major>.<minor>.<patch> のタグが読めない" >&2
        return 1
    fi
}

ca_version_code() {
    printf '%s' "$1" | awk -F. '{ c = $1 * 10000 + $2 * 100 + $3; print (c < 1 ? 1 : c) }'
}

ca_module_id() {
    _p=${CA_MODULE_PROP:-module/module.prop}
    _id=$(sed -n 's/^id=\([^[:space:]]\{1,\}\)[[:space:]]*$/\1/p' "$_p" 2>/dev/null)
    if [ -z "$_id" ] || [ "$(printf '%s\n' "$_id" | wc -l)" -ne 1 ]; then
        echo "$_p の id= を 1 つだけ読めない" >&2
        return 1
    fi
    printf '%s\n' "$_id"
}

ca_system_prop() {
    _dir=$(ca_module_id) || return 1
    printf 'ro.codecanchor.module_version=%s\n' "$2"
    printf 'ro.codecanchor.module_semver=%s\n' "$1"
    printf 'ro.codecanchor.module_dir=/data/adb/modules/%s\n' "$_dir"
}

ca_zip_name() {
    _id=$(ca_module_id) || return 1
    printf '%s-%s%s.zip\n' "$_id" "$CA_MODULE_TAG_PREFIX" "$1"
}

ca_update_json_url() {
    _p=${CA_MODULE_PROP:-module/module.prop}
    _u=$(sed -n 's/^updateJson=\([^[:space:]]\{1,\}\)[[:space:]]*$/\1/p' "$_p" 2>/dev/null)
    if [ -z "$_u" ] || [ "$(printf '%s\n' "$_u" | wc -l)" -ne 1 ]; then
        echo "$_p の updateJson= を 1 つだけ読めない" >&2
        return 1
    fi
    printf '%s\n' "$_u"
}

ca_update_repo() {
    _u=$(ca_update_json_url) || return 1
    _r=$(printf '%s\n' "$_u" | sed -n \
        "s#^https://raw\.githubusercontent\.com/\([^/]\{1,\}/[^/]\{1,\}\)/$CA_UPDATE_BRANCH/$CA_UPDATE_JSON\$#\1#p")
    if [ -z "$_r" ]; then
        echo "updateJson= が https://raw.githubusercontent.com/<owner>/<repo>/$CA_UPDATE_BRANCH/$CA_UPDATE_JSON ではない: $_u" >&2
        return 1
    fi
    printf '%s\n' "$_r"
}

ca_update_url() {
    printf 'https://raw.githubusercontent.com/%s/%s/%s\n' "$1" "$CA_UPDATE_BRANCH" "$2"
}

ca_update_json() {
    _semver=$1
    _code=$2
    _repo=$(ca_update_repo) || return 1
    _zip=$(ca_zip_name "$_semver") || return 1
    printf '{\n'
    printf '  "version": "%s",\n' "$_semver"
    printf '  "versionCode": %s,\n' "$_code"
    printf '  "zipUrl": "https://github.com/%s/releases/download/%s%s/%s",\n' \
        "$_repo" "$CA_MODULE_TAG_PREFIX" "$_semver" "$_zip"
    printf '  "changelog": "%s"\n' "$(ca_update_url "$_repo" "$CA_UPDATE_CHANGELOG")"
    printf '}\n'
}

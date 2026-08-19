ca_version_name() {
    _n=${CA_VERSION_NAME:-}
    [ -n "$_n" ] || _n=$(git describe --tags --abbrev=0 2>/dev/null)
    _n=${_n#v}
    if printf '%s' "$_n" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        printf '%s\n' "$_n"
    else
        printf '0.0.0\n'
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

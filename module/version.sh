# モジュールの版を決める。ホスト側 (build.sh とテスト) でしか使わないので zip には入れない。
#
# 版は semver で、出どころは git のタグ 1 つだけ。build.gradle.kts と同じ規則にしてある。
# モジュールの中身 (libcaeq.so / caeqstat / dlopen_check) は APK から取り出したものなので、
# モジュールの版はアプリの版と必ず一致していなければならない。ここに別の literal を持つと、
# そのずれ自体が「版ずれの検出」で見つけたい事故になる。

# 直近のタグから versionName を作る。CA_VERSION_NAME があればそれを使う (CI / 手動ビルド用)。
# タグが取れない環境 (浅い clone、アーカイブ展開) では 0.0.0。
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

# versionName から versionCode を作る。build.gradle.kts / release.yml と同じ式
# (major * 10000 + minor * 100 + patch)。0 は使わないので下限は 1。
ca_version_code() {
    printf '%s' "$1" | awk -F. '{ c = $1 * 10000 + $2 * 100 + $3; print (c < 1 ? 1 : c) }'
}

# モジュールの id。マネージャが /data/adb/modules/<id>/ に展開するので、
# 置き場もここから決まる。id の出どころは module.prop 1 つだけ —
# 別の literal を持つと、そのずれ自体が「アプリからスクリプトを呼べない」事故になる。
ca_module_id() {
    _p=${CA_MODULE_PROP:-module/module.prop}
    _id=$(sed -n 's/^id=\([^[:space:]]\{1,\}\)[[:space:]]*$/\1/p' "$_p" 2>/dev/null)
    if [ -z "$_id" ] || [ "$(printf '%s\n' "$_id" | wc -l)" -ne 1 ]; then
        echo "$_p の id= を 1 つだけ読めない" >&2
        return 1
    fi
    printf '%s\n' "$_id"
}

# ca_system_prop <versionName> <versionCode>
#   system.prop の中身。アプリが読む側なので、キーの綴りと値の形はここ 1 箇所で決める。
#   /data/adb はアプリから読めないので module.prop を直接見る経路は使えない。
#   ro.* は property_contexts の catch-all で default_prop になり、root なしで読める。
#   コメント行は書かない — Magisk / KernelSU / APatch の 3 者で扱いが同じである保証がない。
ca_system_prop() {
    _dir=$(ca_module_id) || return 1
    printf 'ro.codecanchor.module_version=%s\n' "$2"
    printf 'ro.codecanchor.module_semver=%s\n' "$1"
    printf 'ro.codecanchor.module_dir=/data/adb/modules/%s\n' "$_dir"
}

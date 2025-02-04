#!/usr/bin/env bash

# dm_download_integration_test_binaries.sh will
# * download all the binaries you need for dm integration testing

# Notice:
# Please don't try the script locally,
# it downloads files for linux platform.

set -o errexit
set -o nounset
set -o pipefail

# See https://misc.flogisoft.com/bash/tip_colors_and_formatting.
color-green() { # Green
	echo -e "\x1B[1;32m${*}\x1B[0m"
}

function download() {
	local url=$1
	local file_name=$2
	local file_path=$3
	if [[ -f "${file_path}" ]]; then
		echo "file ${file_name} already exists, skip download"
		return
	fi
	echo ">>>"
	echo "download ${file_name} from ${url}"
	wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "${file_path}" "${url}"
}

# Specify the download branch.
branch=${1:-release-6.5-fips}
default_target_branch="release-6.5"

# PingCAP file server URL.
file_server_url="http://fileserver.pingcap.net"

# Get sha1 based on branch name.
tidb_sha1=$(curl "${file_server_url}/download/refs/pingcap/tidb/${branch}/sha1")
tikv_sha1=$(curl "${file_server_url}/download/refs/pingcap/tikv/${branch}/sha1")
pd_sha1=$(curl "${file_server_url}/download/refs/pingcap/pd/${branch}/sha1")
tidb_tools_sha1=$(curl "${file_server_url}/download/refs/pingcap/tidb-tools/master/sha1")

# All download links.
tidb_download_url="${file_server_url}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
tikv_download_url="${file_server_url}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
pd_download_url="${file_server_url}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
tidb_tools_download_url="${file_server_url}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz"

gh_os_download_url="https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz"
minio_download_url="${file_server_url}/download/minio.tar.gz"

# Some temporary dir.
rm -rf tmp
rm -rf third_bin

mkdir -p third_bin
mkdir -p tmp
mkdir -p bin

color-green "Download binaries..."
download "$tidb_download_url" "tidb-server.tar.gz" "tmp/tidb-server.tar.gz"
tar -xz -C third_bin bin/tidb-server -f tmp/tidb-server.tar.gz && mv third_bin/bin/tidb-server third_bin/
download "$pd_download_url" "pd-server.tar.gz" "tmp/pd-server.tar.gz"
tar -xz -C third_bin 'bin/*' -f tmp/pd-server.tar.gz && mv third_bin/bin/* third_bin/
download "$tikv_download_url" "tikv-server.tar.gz" "tmp/tikv-server.tar.gz"
tar -xz -C third_bin bin/tikv-server -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-server third_bin/
download "$tidb_tools_download_url" "tidb-tools.tar.gz" "tmp/tidb-tools.tar.gz"
tar -xz -C third_bin 'bin/sync_diff_inspector' -f tmp/tidb-tools.tar.gz && mv third_bin/bin/sync_diff_inspector third_bin/
download "$minio_download_url" "minio.tar.gz" "tmp/minio.tar.gz"
tar -xz -C third_bin -f tmp/minio.tar.gz
download "$gh_os_download_url" "gh-ost-binary-linux-20200828140552.tar.gz" "tmp/gh-ost-binary-linux-20200828140552.tar.gz"
tar -xz -C third_bin -f tmp/gh-ost-binary-linux-20200828140552.tar.gz

chmod a+x third_bin/*

# Copy it to the bin directory in the root directory.
rm -rf tmp
rm -rf bin/bin
mv third_bin/* ./bin
rm -rf third_bin
rm -rf bin/bin

color-green "Download SUCCESS"

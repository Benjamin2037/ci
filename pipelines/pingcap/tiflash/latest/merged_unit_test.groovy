// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"  // TODO: need to adjust namespace after test
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-merged_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final dependency_dir = "/home/jenkins/agent/dependency"
final proxy_cache_dir = "/home/jenkins/agent/proxy-cache/refactor-pipelines"
Boolean proxy_cache_ready = false
Boolean update_proxy_cache = true
Boolean update_ccache = true
String proxy_commit_hash = null

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
            retries 5
            customWorkspace "/home/jenkins/agent/workspace/tiflash-build-common"
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 120, unit: 'MINUTES') }
            steps {
                    dir("tiflash") {
                        script {
                            cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                                retry(2) {
                                    prow.checkoutRefs(REFS, timeout = 10, credentialsId = '', gitBaseUrl = 'https://github.com')
                                }
                            }
                            cache(path: ".git/modules", filter: '**/*', key: prow.getCacheKey('git', REFS, 'git-modules'), restoreKeys: prow.getRestoreKeys('git', REFS, 'git-modules')) {
                                    sh ''
                                    sh """
                                    git submodule update --init --recursive
                                    git status
                                    git show --oneline -s
                                    """
                            }
                            dir("contrib/tiflash-proxy") {
                                proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                                println "proxy_commit_hash: ${proxy_commit_hash}"
                            }
                            sh """
                            chown 1000:1000 -R ./
                            """
                        }
                    }
            }
        }
        stage("Prepare tools") {
            // TODO: need to simplify this part
            // all tools should be pre-install in docker image
            parallel {
            stage("Ccache") {
                steps {
                    sh label: "install ccache", script: """
                        if ! command -v ccache &> /dev/null; then
                            echo "ccache not found! Installing..."
                            rpm -Uvh '${dependency_dir}/ccache.x86_64.rpm'
                        else
                            echo "ccache is already installed!"
                        fi
                    """
                }
            }
            stage("Cmake") {
                steps { 
                    sh label: "install cmake3", script: """
                        if ! command -v cmake &> /dev/null; then
                            echo "cmake not found! Installing..."
                            sh ${dependency_dir}/cmake-3.22.3-linux-x86_64.sh --prefix=/usr --skip-license --exclude-subdir
                        else
                            echo "cmake is already installed!"
                        fi
                    """
                }
            }
            stage("Clang-Format") {
                steps {
                    sh label: "install clang-format", script: """
                        if ! command -v clang-format &> /dev/null; then
                            echo "clang-format not found! Installing..."
                            cp '${dependency_dir}/clang-format-12' '/usr/local/bin/clang-format'
                            chmod +x '/usr/local/bin/clang-format'
                        else
                            echo "clang-format is already installed!"
                        fi
                    """
                }
            }
            stage("Clang-Format-15") {
                steps { 
                    sh label: "install clang-format-15", script: """
                        if ! command -v clang-format-15 &> /dev/null; then
                            echo "clang-format-15 not found! Installing..."
                            cp '${dependency_dir}/clang-format-15' '/usr/local/bin/clang-format-15'
                            chmod +x '/usr/local/bin/clang-format-15'
                        else
                            echo "clang-format-15 is already installed!"
                        fi
                    """
                }
            }
            stage( "Clang-Tidy") {
                steps { 
                    sh label: "install clang-tidy", script: """
                        if ! command -v clang-tidy &> /dev/null; then
                            echo "clang-tidy not found! Installing..."
                            cp '${dependency_dir}/clang-tidy-12' '/usr/local/bin/clang-tidy'
                            chmod +x '/usr/local/bin/clang-tidy'
                            cp '${dependency_dir}/lib64-clang-12-include.tar.gz' '/tmp/lib64-clang-12-include.tar.gz'
                            cd /tmp && tar zxf lib64-clang-12-include.tar.gz
                        else
                            echo "clang-tidy is already installed!"
                        fi
                    """
                }
            }
            stage("Coverage") {
                steps {
                    sh label: "install gcovr", script: """
                        if ! command -v gcovr &> /dev/null; then
                            echo "lcov not found! Installing..."
                            cp '${dependency_dir}/gcovr.tar' '/tmp/'
                            cd /tmp
                            tar xvf gcovr.tar && rm -rf gcovr.tar
                            ln -sf /tmp/gcovr/gcovr /usr/bin/gcovr
                        else
                            echo "lcov is already installed!"
                        fi
                    """
                }
            }
            }
        }

        stage("Prepare Cache") {
            parallel {
                stage("Ccache") {
                    steps {
                    script { 
                        dir("tiflash") {
                            sh label: "copy ccache if exist", script: """
                            pwd
                            ccache_tar_file="/home/jenkins/agent/ccache/master-merged-unit-test/pagetools-tests-amd64-linux-llvm-debug-master-cov-failpoints.tar"
                            if [ -f \$ccache_tar_file ]; then
                                echo "ccache found"
                                cd /tmp
                                cp -r \$ccache_tar_file ccache.tar
                                tar -xf ccache.tar
                                ls -lha /tmp
                                ls -lha /tmp/.ccache
                            else
                                echo "ccache not found"
                            fi
                            """
                            sh label: "config ccache", script: """
                            ccache -o cache_dir="/tmp/.ccache"
                            ccache -o max_size=2G
                            ccache -o limit_multiple=0.99
                            ccache -o hash_dir=false
                            ccache -o compression=true
                            ccache -o compression_level=6
                            ccache -o read_only=false
                            ccache -z
                            """
                        }
                    }
                    }
                }
                stage("Proxy-Cache") {
                    steps {
                        script {
                            proxy_cache_ready = fileExists("/home/jenkins/agent/proxy-cache/refactor-pipelines/${proxy_commit_hash}-amd64-linux-llvm")
                            println "proxy_cache_ready: ${proxy_cache_ready}"

                            sh label: "copy proxy if exist", script: """
                            proxy_cache_file="${proxy_cache_dir}/${proxy_commit_hash}-amd64-linux-llvm"
                            if [ -f \$proxy_cache_file ]; then
                                echo "proxy cache found"
                                mkdir -p ${WORKSPACE}/tiflash/libs/libtiflash-proxy
                                cp \$proxy_cache_file  ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                                chmod +x  ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                            else
                                echo "proxy cache not found"
                            fi
                            """
                        }   
                    }
                }
                stage("Cargo-Cache") {
                    steps {
                        sh label: "link cargo cache", script: """
                            mkdir -p ~/.cargo/registry
                            mkdir -p ~/.cargo/git
                            mkdir -p /home/jenkins/agent/rust/registry/cache
                            mkdir -p /home/jenkins/agent/rust/registry/index
                            mkdir -p /home/jenkins/agent/rust/git/db
                            mkdir -p /home/jenkins/agent/rust/git/checkouts

                            rm -rf ~/.cargo/registry/cache && ln -s /home/jenkins/agent/rust/registry/cache ~/.cargo/registry/cache
                            rm -rf ~/.cargo/registry/index && ln -s /home/jenkins/agent/rust/registry/index ~/.cargo/registry/index
                            rm -rf ~/.cargo/git/db && ln -s /home/jenkins/agent/rust/git/db ~/.cargo/git/db
                            rm -rf ~/.cargo/git/checkouts && ln -s /home/jenkins/agent/rust/git/checkouts ~/.cargo/git/checkouts

                            rm -rf ~/.rustup/tmp
                            rm -rf ~/.rustup/toolchains
                            mkdir -p /home/jenkins/agent/rust/rustup-env/tmp
                            mkdir -p /home/jenkins/agent/rust/rustup-env/toolchains
                            ln -s /home/jenkins/agent/rust/rustup-env/tmp ~/.rustup/tmp
                            ln -s /home/jenkins/agent/rust/rustup-env/toolchains ~/.rustup/toolchains
                        """
                    }
                }
            }
        }
        stage("Build Dependency and Utils") {
            parallel {
                stage("Cluster Manage") { 
                    steps {
                    // NOTE: cluster_manager is deprecated since release-6.0 (include)
                    echo "cluster_manager is deprecated"
                    }
                }
                stage("TiFlash Proxy") {
                    steps {
                        script {
                            if (proxy_cache_ready) {
                                echo "skip becuase of cache"
                            } else {
                                echo "proxy cache not ready"
                                echo "skip because proxy build is integrated"
                            }
                        }
                    }
                }
            }
        }
        stage("Configure Project") {
            steps {
                script {
                    def toolchain = "llvm"
                    def generator = 'Ninja'
                    def coverage_flag = "-DTEST_LLVM_COVERAGE=ON"
                    def diagnostic_flag = ""
                    def compatible_flag = ""
                    def openssl_root_dir = ""
                    def prebuilt_dir_flag = ""
                    if (proxy_cache_ready) {
                        // only for toolchain is llvm
                        prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy/'"
                        sh """
                        mkdir -p ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release
                        cp ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release/
                        """
                    }
                    // create build dir and install dir
                    sh label: "create build & install dir", script: """
                    mkdir -p ${WORKSPACE}/build
                    mkdir -p ${WORKSPACE}/install/tiflash
                    """
                    dir("${WORKSPACE}/build") {
                        sh label: "configure project", script: """
                        cmake '${WORKSPACE}/tiflash' ${prebuilt_dir_flag} ${coverage_flag} ${diagnostic_flag} ${compatible_flag} ${openssl_root_dir} \\
                            -G '${generator}' \\
                            -DENABLE_FAILPOINTS=true \\
                            -DCMAKE_BUILD_TYPE=Debug \\
                            -DCMAKE_PREFIX_PATH='/usr/local' \\
                            -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                            -DENABLE_TESTS=true \\
                            -DUSE_CCACHE=true \\
                            -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                            -DUSE_INTERNAL_TIFLASH_PROXY=${!proxy_cache_ready} \\
                            -DRUN_HAVE_STD_REGEX=0 \\
                        """
                    }
                }
            }
        }
        stage("Build TiFlash") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    cmake --build . --target help || true
                    cmake --build . --target help | grep page_ctl || true
                    cmake --build . --target help | grep page_stress_testing || true
                    cmake --build . --target help | grep gtests_libdaemon || true
                    cmake --build '${WORKSPACE}/build' --target gtests_dbms gtests_libcommon gtests_libdaemon --parallel 12
                    """
                    sh """
                    cp '${WORKSPACE}/build/dbms/gtests_dbms' '${WORKSPACE}/install/tiflash/'
                    cp '${WORKSPACE}/build/libs/libcommon/src/tests/gtests_libcommon' '${WORKSPACE}/install/tiflash/'
                    cmake --install ${WORKSPACE}/build --component=tiflash-gtest --prefix='${WORKSPACE}/install/tiflash'
                    """
                }
                dir("${WORKSPACE}/build") {
                    sh """
                    target=`realpath \$(find . -executable | grep -v gtests_libdaemon.dir | grep gtests_libdaemon)`
                    cp \$target '${WORKSPACE}/install/tiflash/'
                    """
                }
                sh """
                ccache -s
                ls -lha ${WORKSPACE}/install/tiflash/
                """
            }
        }
        stage("Post Build") {
            failFast true
            parallel {
                stage("Archive Build Artifacts") {
                    steps {
                        dir("${WORKSPACE}/install") {
                            sh """
                            tar -czf 'tiflash.tar.gz' 'tiflash'
                            ls -alh
                            """
                            archiveArtifacts artifacts: "tiflash.tar.gz"
                        }
                    }
                }
                stage("Archive Build Data") {
                    steps {
                        dir("${WORKSPACE}/build") {
                            sh """
                            tar -cavf build-data.tar.xz \$(find . -name "*.h" -o -name "*.cpp" -o -name "*.cc" -o -name "*.hpp" -o -name "*.gcno" -o -name "*.gcna")
                            ls -alh
                            """
                            archiveArtifacts artifacts: "build-data.tar.xz", allowEmptyArchive: true
                        }
                        dir("${WORKSPACE}/tiflash") {
                            sh """
                            tar -cavf source-patch.tar.xz \$(find . -name "*.pb.h" -o -name "*.pb.cc")
                            ls -alh
                            """
                            archiveArtifacts artifacts: "source-patch.tar.xz", allowEmptyArchive: true
                        }
                    }
                }
                stage("Update Ccache") {
                    when {
                        expression { return update_ccache }
                    }
                    steps {
                        dir("${WORKSPACE}/tiflash") {
                            sh """
                            ccache_tar_file="/home/jenkins/agent/ccache/master-merged-unit-test/pagetools-tests-amd64-linux-llvm-debug-master-cov-failpoints.tar"
                            cd /tmp
                            rm -rf ccache.tar
                            tar -cf ccache.tar .ccache
                            cp ccache.tar \${ccache_tar_file}
                            cd -
                            """
                        }
                    }
                }
            }
        }
        stage("Unit Test Prepare") {
            steps {
                sh """
                ln -sf ${WORKSPACE}/install/tiflash /tiflash
                """
                sh """
                ls -lha ${WORKSPACE}/tiflash
                ln -sf ${WORKSPACE}/tiflash/tests /tests
                """
                dir("${WORKSPACE}/tiflash") {
                    echo "temp skip here"
                }
                dir("${WORKSPACE}/build") {
                    echo "temp skip here"
                }
            }
        }
        stage("Run Tests") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    parallelism=12
                    rm -rf /tmp-memfs/tiflash-tests
                    mkdir -p /tmp-memfs/tiflash-tests
                    export TIFLASH_TEMP_DIR=/tmp-memfs/tiflash-tests

                    mkdir -p /root/.cache
                    source /tests/docker/util.sh
                    export LLVM_PROFILE_FILE="/tiflash/profile/unit-test-%\${parallelism}m.profraw"
                    show_env
                    ENV_VARS_PATH=/tests/docker/_env.sh OUTPUT_XML=true NPROC=\${parallelism} /tests/run-gtest.sh
                    """
                }
            }
        }

        stage("Coverage") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    if ! command -v lcov &> /dev/null; then
                        echo "lcov not found! Installing..."
                        rpm -i /home/jenkins/agent/dependency/lcov-1.15-1.noarch.rpm
                        which lcov
                        which genhtml
                    else
                        echo "lcov is already installed!"
                    fi
                    llvm-profdata merge -sparse /tiflash/profile/*.profraw -o /tiflash/profile/merged.profdata
                    
                    export LD_LIBRARY_PATH=.
                    llvm-cov export \\
                        /tiflash/gtests_dbms /tiflash/gtests_libcommon /tiflash/gtests_libdaemon \\
                        --format=lcov \\
                        --instr-profile /tiflash/profile/merged.profdata \\
                        --ignore-filename-regex "/usr/include/.*" \\
                        --ignore-filename-regex "/usr/local/.*" \\
                        --ignore-filename-regex "/usr/lib/.*" \\
                        --ignore-filename-regex ".*/contrib/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Debug/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Client/.*" \\
                        > /tiflash/profile/lcov.info

                    mkdir -p /tiflash/report
                    genhtml /tiflash/profile/lcov.info -o /tiflash/report/ --ignore-errors source

                    llvm-cov show \\
                        /tiflash/gtests_dbms /tiflash/gtests_libcommon /tiflash/gtests_libdaemon \\
                        --instr-profile /tiflash/profile/merged.profdata \\
                        --ignore-filename-regex "/usr/include/.*" \\
                        --ignore-filename-regex "/usr/local/.*" \\
                        --ignore-filename-regex "/usr/lib/.*" \\
                        --ignore-filename-regex ".*/contrib/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Debug/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Client/.*" \\
                        > /tiflash/profile/coverage.txt

                    pushd /tiflash
                        tar -czf coverage-report.tar.gz report
                        mv coverage-report.tar.gz ${WORKSPACE}
                    popd
                    """
                }
                archiveArtifacts artifacts: "/tiflash/profile/**", allowEmptyArchive: true
                archiveArtifacts artifacts: "/tiflash/report/**", allowEmptyArchive: true
            }
        }
    }
}

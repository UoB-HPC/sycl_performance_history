# Mini-app benchmarks for various SYCL implementations.

This program emits shell scripts and job scripts based on the requested project-sycl-platform triple.

## Buildling

Prerequisites: 
 
 * Java 11
 
For fresh environments with no Java installed, the recommended way is to use coursier which install Java and Sbt for the current user:

    curl -Lo cs https://git.io/coursier-cli-linux && chmod +x cs && ./cs setup --jdk 11

Proceed with building the executable:

```shell
> cd benchmark
> ./sbt assembly
> java -jar benchmark.jar 

help                     - print this help
prime                    - download/copy compilers and prime them for use
  <pool:dir>               - directory containing ComputeCpp tarballs
list                     - lists all SYCL compilers, platforms,  and projects
bench                    - run benchmarks
  [<project:string>|all]   - the projects to run, see `list`
  <sycl:glob>              - blob pattern of the sycl config tuple to use, see `list`
  <platform:glob>          - the platforms to run on, see `list` 
  <out:dir>                - output directory for results and intermediate build files
  <par:int>                - compile projects with the specified parallelism

```

To run benchmarks, pass the appropriate configurations.
For example, the following runs babelstream for all hipsycl versions on Cascade Lake.

```shell
> java -jar benchmark.jar bench babelstream hipsycl-* cxl-* ../output 20
```
The script will execute the generated scripts and submit them for you before exiting.

package io.github.ravenliao.svg2vd.contract

enum class ExitCode(val value: Int) {
    SUCCESS(0),
    USAGE(2),
    CONVERSION_FAILURE(3),
    ENVIRONMENT(4),
    INTERNAL(5),
}

`lift_vertical.json` is the exact40-lift table provided by the user from the course.
It is copied unchanged from common/src/main/resources/lift_vertical.json. Production
compose mounts this file at /config/lift_vertical.json; the application also embeds
that official table as its no-file default. An explicitly configured missing or
invalid file is an error, not permission to substitute invented values.

For explicitly labelled LOCAL fixture testing only, use compose.demo.yaml. It sets
ALLOW_DEMO_LIFTS=true and clears LIFT_VERTICAL_FILE, selecting the synthetic fixture.
That fixture must not replace the official table in submitted benchmarks.

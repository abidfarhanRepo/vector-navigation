# Runbook — vector-privacy

## Run tests

```sh
cd vector-privacy
PYTHONPATH=src python -m unittest discover -s tests -v
node scripts/validate.mjs
```

## Regenerate the vendored copy in vector-web

The library is vendored into `vector-web/vendor/vector_privacy`. To refresh:

```sh
# from vector-web
cp -r ../vector-privacy/src/vector_privacy vendor/vector_privacy
```

Then run `vector-web`'s own test suite to confirm the vendored copy matches.

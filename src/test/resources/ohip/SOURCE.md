# OHIP contract (test resources)

Oracle's published OHIP specifications, trimmed to the operations the bridge calls by `tools/ohip_specs.py`; nothing in a kept operation is changed. `OhipContractTest` checks every exchange between the OPERA adapter and the simulator against them.

- Source: https://github.com/oracle/hospitality-api-docs at commit `a34e540b0368e8eb8efa0bf59086576371cfacd2`
- Files: `rest-api-specs/property/v1/csh.json`, `rest-api-specs/property/v1/bof.json`, `rest-api-specs/security/v1/publishedoauth.json`
- Operations: postBillingPayment, getFolio, getBusinessDate, getToken
- Licence: Universal Permissive License 1.0, copyright Oracle and/or its affiliates (`LICENSE.txt`, copied as the licence requires)

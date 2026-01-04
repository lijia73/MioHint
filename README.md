# MioHint: LLM-Assisted Request Mutation for Whitebox REST API Testing

In this package, we provide necessary information for replicating the experiment in the paper that includes

## Build MioHint

Go to the root, run

> `mvn clean install -DskipTests`

`jar` file would be found under `core/target`.

> Note: Docker is required to run `genome-nexus` case study.

## Run Experiments

`<nameOfScript>.py <cluster> <baseSeed> <dir> <minSeed> <maxSeed> <budget> <timeoutMinutes> <nJobs> <configFilter?> <sutFilter?>`

The above command will produce scripts to execute the experiments. Additional information about replicating studies can be found on the below provided link.

`env EVOMASTER_DIR=core/target EMB_DIR=dist OPENAI_TOKEN=xxx? python3.6 scripts/exp.py false 12345 llm-exp-1 1 1 1h 1 48`

`env EVOMASTER_DIR=core/target EMB_DIR=dist OPENAI_TOKEN=xxx? python3.6 scripts/exp.py false 12345 llm-exp-2 1 1 1h 1 48`

`env EVOMASTER_DIR=core/target EMB_DIR=dist OPENAI_TOKEN=xxx? python3.6 scripts/exp.py false 12345 llm-exp-3 1 1 1h 1 48`

## Analyse Results

The below archives contains results from the experiments we conducted.

- experiment_origin.zip
- experiment_supplement.zip

You can analyse these using the `scripts/satistic.py <logPath>`, for example

`bash scripts/statistic.sh lm-exp-1/logs/evomaster/`

The analysis outcome is preserved in:

- experiment_result.zip

## Links

We ran experiments on EMB based on the provided guidelines from the authors. You can find more information from these links, if needed.

- [EMB](https://github.com/EMResearch/EMB/blob/master/README.md): you can find more information about building case studies
- [EvoMaster](https://github.com/EMResearch/EvoMaster/blob/master/docs/build.md): you can find more information about building EvoMaster
- [Replicating Studies](https://github.com/EMResearch/EvoMaster/blob/master/docs/replicating_studies.md): you can find more information about how to replicate studies

## Reference

```
@inproceedings{Li2026MioHint,
  title        = {MioHint: LLM-Assisted Request Mutation for Whitebox REST API Testing},
  author       = {Li, Jia and Shen, Jiacheng and Su, Yuxin and Lyu, Michael R.},
  booktitle    = {Proceedings of the 2026 IEEE/ACM 48th International Conference on Software Engineering (ICSE '26)},
  year         = {2026},
  publisher    = {Association for Computing Machinery}},
  doi          = {10.1145/3744916.3773228}
}
```

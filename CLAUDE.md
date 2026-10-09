# minflix

Mini Netflix (VOD): users upload a video, it is transcoded to HLS, and they watch it with a quality selector. One workspace, three projects, one shared contract.

@docs/CONTRACT.md

## Layout
```
minflix/
├── CLAUDE.md              this file
├── docs/CONTRACT.md       shared contract: API, environments, storage layout, prod env vars
├── minflix-be/            Spring Boot API + FFmpeg worker   (own CLAUDE.md)
├── minflix-fe/            React + hls.js frontend           (own CLAUDE.md)
└── minflix-infra/         Terraform for prod on AWS         (own CLAUDE.md)
```
Environments: `dev` = docker compose (lives in minflix-be) + `npm run dev`. `prod` = AWS via minflix-infra.

## Workspace rules
- Work in ONE project per task: the one the user names. Do not edit the other projects unless told.
- Before working in a project, read its `CLAUDE.md` (project files are not all loaded at the start). It holds that project's stack, requirements, docs to write and working method.
- Run commands from inside the project directory (`cd minflix-be`, etc.).
- The contract is the only coupling between projects. Never change it unilaterally: stop, explain the needed change, and wait for the user.
- Never run `terraform apply`, `terraform destroy`, any AWS CLI command that creates/changes/deletes resources, or push container images. The user does these.
- Recommended build order: minflix-be, then minflix-fe, then minflix-infra, then the first prod deploy by the user.

"""Validate the actual shell migration and fallback launcher without changing the host."""
import pathlib, re, shlex, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
script=(root/'src/main/assets/scripts/install-zsh-offline.sh').read_text()
subprocess.run(['sh','-n'],input=script,text=True,check=True)
rc=script.split("<<'ZSH'\n",1)[1].split('\nZSH\n',1)[0]
subprocess.run(['/bin/zsh','-n'],input=rc,text=True,check=True)
line=next(line for line in script.splitlines() if line.startswith("awk -F: -v ab_shell="))
awk=shlex.split(line.split(' /etc/passwd >',1)[0]);awk[awk.index('-v')+1]='ab_shell=/usr/bin/alicebridge-login'
fixture='root:preserve-this-hash:0:0:root:/root:/bin/sh\ndaemon:x:1:1:daemon:/tmp:/bin/false\n'
updated=subprocess.check_output(awk,input=fixture,text=True)
assert updated=='root:preserve-this-hash:0:0:root:/data/opt/home:/usr/bin/alicebridge-login\ndaemon:x:1:1:daemon:/tmp:/bin/false\n'
launcher=script.split("<<'LOGIN'\n",1)[1].split('\nLOGIN\n',1)[0]
with tempfile.TemporaryDirectory() as tmp:
    fake=pathlib.Path(tmp)/'zsh';fake.write_text('#!/bin/sh\nprintf "zsh:%s\\n" "$*"\n');fake.chmod(0o755)
    wrapper=pathlib.Path(tmp)/'login';wrapper.write_text(launcher.replace('/opt/bin/zsh',str(fake)));wrapper.chmod(0o755)
    assert subprocess.check_output([str(wrapper)],text=True)=='zsh:-l\n'
    assert subprocess.check_output([str(wrapper),'-c','printf remote-command'],text=True)=='remote-command'
    fake.unlink()
    assert subprocess.check_output([str(wrapper),'-c','printf fallback-ok'],text=True)=='fallback-ok'
with tempfile.TemporaryDirectory() as tmp:
    directory=pathlib.Path(tmp);rcfile=directory/'.zshrc';rcfile.write_text(rc)
    command='source '+shlex.quote(str(rcfile))+'; [[ -n "$PROMPT" ]] && (( $+functions[compdef] )) && [[ -n "$(whence -p zsh)" ]] && print CONFIGURED'
    import os
    result=subprocess.run(['/bin/zsh','-dfi','-c',command],env=os.environ|{'HOME':tmp,'ZDOTDIR':tmp,'TERM':'xterm-256color'},text=True,capture_output=True)
    assert result.returncode==0 and 'CONFIGURED' in result.stdout and 'command not found' not in result.stderr and 'failed to load' not in result.stderr,(result.stdout,result.stderr)
repair=(root/'src/main/assets/scripts/repair-zsh-environment.sh').read_text()
subprocess.run(['sh','-n'],input=repair,text=True,check=True)
paths=repair.split("<<'PATHS'\n",1)[1].split('\nPATHS\n',1)[0]
with tempfile.TemporaryDirectory() as tmp:
    directory=pathlib.Path(tmp);custom=directory/'custom-bin';custom.mkdir();fake=custom/'custom-tool';fake.write_text('#!/bin/sh\necho CUSTOM_TOOL_OK\n');fake.chmod(0o755)
    pathfile=directory/'paths.zsh';pathfile.write_text(paths)
    command='PATH='+shlex.quote(str(custom))+':/usr/bin:/bin; source '+shlex.quote(str(pathfile))+'; whence -p custom-tool; [[ ":$PATH:" == *:/usr/local/bin:* && ":$PATH:" == *:/data/local/bin:* ]] && custom-tool'
    result=subprocess.run(['/bin/zsh','-df','-c',command],text=True,capture_output=True)
    assert result.returncode==0 and 'CUSTOM_TOOL_OK' in result.stdout,(result.stdout,result.stderr)
assert 'exec "$factory" "$@"' in launcher and '/usr/local/bin' in launcher and '/data/local/bin' in launcher
assert 'source "$ZDOTDIR/paths.zsh"' in repair
print('PASS: existing customized PATH and native package directories preserved; noninteractive commands use POSIX shell')
print('PASS: Zsh initializes prompt/completion/PATH; preserves passwords/accounts; forwards SSH commands; falls back to factory shell')

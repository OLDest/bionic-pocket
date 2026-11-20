import { execFile } from 'node:child_process';

export function validateCommands(value = []) {
  if (!Array.isArray(value) || value.length > 8) throw Error('At most eight coordinator-approved commands are allowed');
  const ids = new Set();
  for (const command of value) {
    if (!/^[a-z0-9-]{1,64}$/.test(command.id) || ids.has(command.id)) throw Error('Invalid/duplicate command ID');
    ids.add(command.id);
    if (typeof command.program !== 'string' || !command.program || !Array.isArray(command.args) || command.args.some(a => typeof a !== 'string') || JSON.stringify(command).length > 16000) throw Error('Invalid command specification');
    if (command.env && (typeof command.env !== 'object' || Array.isArray(command.env) || Object.values(command.env).some(v => typeof v !== 'string'))) throw Error('Invalid command environment');
    if(command.rerunOnChanges&&(!Array.isArray(command.rerunOnChanges)||command.rerunOnChanges.some(value=>typeof value!=='string')))throw Error('Invalid command rerun paths');
    if(command.contextRole!==undefined&&command.contextRole!=='source')throw Error('Invalid command contextRole');
    if(command.contextRole==='source'&&(!Array.isArray(command.rerunOnChanges)||!command.rerunOnChanges.length||!Number.isInteger(command.maxSourceChars)||command.maxSourceChars<1||command.maxSourceChars>6000))throw Error('Source commands require watched paths and maxSourceChars1..6000');
  }
  for (const command of value) if (command.requiresSuccess && (!Array.isArray(command.requiresSuccess) || command.requiresSuccess.some(id => !ids.has(id) || id === command.id))) throw Error('Invalid command prerequisite');
  return value;
}
export function executeCommand(command, cwd, options = {}) {
  return new Promise(resolve => {
    execFile(options.rtkPath ?? 'rtk', ['proxy', command.program, ...command.args], {
      cwd, env: { ...process.env, ...options.env, ...command.env }, windowsHide: true,
      timeout: Math.max(1000, Math.min(command.timeoutMs ?? 30000, 120000)), maxBuffer: 32768,
      encoding: 'utf8'
    }, (error, stdout, stderr) => resolve({ id: command.id, exitCode: error ? (typeof error.code === 'number' ? error.code : -1) : 0,
      stdout: stdout.slice(0, 10000), stderr: stderr.slice(0, 10000), timedOut: Boolean(error?.killed), completedAt: new Date().toISOString() }));
  });
}

export function executeShell(script, cwd, options = {}) {
  if (typeof script !== 'string' || !script.trim() || script.length > 16000) throw Error('Invalid shell script');
  const encodedScript=Buffer.from(script,'utf8').toString('base64');
  const bootstrap=`
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$OutputEncoding=[Console]::OutputEncoding
$env:PSModulePath="$env:SystemRoot\\System32\\WindowsPowerShell\\v1.0\\Modules"
$taskScript=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedScript}'))
$tokens=$null; $parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($taskScript,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Invalid PowerShell syntax. Use Windows PowerShell cmdlets; external programs must use rtk proxy.'}
$linux=@('ls','cat','head','tail','grep','sed','awk','find','rm','cp','mv','touch','chmod','chown','sudo','bash','sh','zsh')
$functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)|ForEach-Object {$_.Name})
$commands=$ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)
foreach($command in $commands){
  $name=$command.GetCommandName()
  if(!$name){throw 'Dynamic command invocation is not supported. Use a literal PowerShell cmdlet or rtk proxy executable.'}
  $leaf=[IO.Path]::GetFileName($name).ToLowerInvariant()
  if($linux -contains $leaf){throw "Linux command '$name' is disabled. Use Get-ChildItem -Force, Get-Content, Select-String, Select-Object -First/-Last. External programs: rtk proxy <program> <args>."}
  if($leaf -in @('rtk','rtk.exe')){
    $index=1
    if($command.CommandElements.Count -gt 1 -and $command.CommandElements[1].Extent.Text -eq 'proxy'){$index=2}
    if($command.CommandElements.Count -gt $index){
      $target=$command.CommandElements[$index].Extent.Text.Trim([char]39,[char]34)
      if($linux -contains [IO.Path]::GetFileName($target).ToLowerInvariant()){throw "Linux target '$target' is disabled even through RTK. Use PowerShell cmdlets or installed Windows utilities."}
    }
    continue
  }
  if($functions -contains $name){continue}
  $resolved=Get-Command -Name $name -ErrorAction SilentlyContinue
  if(!$resolved -or $resolved.CommandType -notin @('Cmdlet','Function','Filter')){throw "External command '$name' requires rtk proxy. PowerShell commands use full cmdlet names."}
}
& ([ScriptBlock]::Create($taskScript))
`;
  return executeCommand({ id: options.id ?? 'shell', program: options.powershellPath ?? 'powershell',
    args: ['-NoProfile', '-NonInteractive', '-Command', bootstrap], timeoutMs: options.timeoutMs }, cwd, options);
}

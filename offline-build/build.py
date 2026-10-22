#!/usr/bin/env python3
"""Compile the plugins offline. Requires only Python 3 and a JDK (11+)."""
import hashlib,os,pathlib,shutil,subprocess,sys,zipfile
ROOT=pathlib.Path(__file__).resolve().parent
WORKSPACE=ROOT.parent.parent
os.chdir(ROOT)
from tools.validate_plugin import validate_public_plugin
def module_root(name):
 return ROOT/name if name == 'Storage' else WORKSPACE/name
def tool(name):
 home=os.environ.get('JAVA_HOME');path=str(pathlib.Path(home)/'bin'/name) if home else shutil.which(name)
 if not path:sys.exit('Set JAVA_HOME to a JDK 11 or newer (missing '+name+').')
 return path
JAVAC=tool('javac')
LIBS=sorted((ROOT/'vendor/lib').glob('*.jar'))
for line in (ROOT/'vendor/SHA256SUMS').read_text().splitlines():
 expected,relative=line.split('  ',1)
 library=ROOT/'vendor'/relative
 if not library.is_file() or hashlib.sha256(library.read_bytes()).hexdigest()!=expected:
  sys.exit('Bundled dependency is missing or corrupt: '+str(library))

TARGET=ROOT/'work/build'
TARGET.mkdir(parents=True,exist_ok=True)
LOMBOK='lombok.launch.AnnotationProcessorHider$AnnotationProcessor'
PROCESSOR='network.palace.core.plugin.processor.PluginInfoProcessor'
def compile_module(name,extra=(),processor=True,sources=None):
 dest=TARGET/name
 if dest.exists():shutil.rmtree(dest)
 dest.mkdir()
 libs=[p for p in LIBS if not any(x in p.name for x in (['spigot-api-1.16','worldedit-7','worldguard-7'] if name!='Creative' else ['spigot-api-1.12','worldedit-6','worldguard-6']))]
 cp=os.pathsep.join(map(str,[*extra,*libs]))
 src=sources or sorted((module_root(name)/'src/main/java').rglob('*.java'))
 args=['--release','11','-encoding','UTF-8','-parameters','-cp',cp,'-d',str(dest)]
 if processor:args+=['-processorpath',cp,'-processor',LOMBOK+(','+PROCESSOR if name not in ('Storage','Processor','PalaceBungee') else '')]
 else:args+=['-proc:none']
 argfile=TARGET/(name+'.args')
 argfile.write_text('\n'.join('"'+str(x).replace('\\','\\\\').replace('"','\\"')+'"' for x in [*args,*src]))
 print('Compiling '+name,flush=True)
 subprocess.run([JAVAC,'@'+str(argfile)],check=True)
 resources=module_root(name)/'src/main/resources'
 if resources.exists():shutil.copytree(resources,dest,dirs_exist_ok=True)
 return dest
storage=compile_module('Storage',processor=False)
bootstrap=list((WORKSPACE/'Core/src/main/java/network/palace/core/plugin/processor').glob('*.java'))+[WORKSPACE/'Core/src/main/java/network/palace/core/plugin/PluginInfo.java']
processor=compile_module('Processor',processor=False,sources=bootstrap)
core=compile_module('Core',[storage,processor])
core_tests=compile_module('CoreTests',[core],processor=False,sources=sorted((ROOT/'CoreTests/src/main/java').rglob('*.java')))
java=str(pathlib.Path(JAVAC).with_name('java'))
core_test_cp=os.pathsep.join(map(str,[core_tests,core,storage,*LIBS]))
for test in ['network.palace.core.config.ConfigurationLoadingTest','network.palace.core.messagequeue.MessageHandlerTest','network.palace.core.mongo.ParkJoinDataTest']:
 subprocess.run([java,'-cp',core_test_cp,test],check=True)
modules={}
for name in ['Vanish','ParkWarp','Pictify','PalaceBungee','Lobby','Show','RideManager','ParkManager','Photopass','Creative']:
 modules[name]=compile_module(name,[core,storage,*modules.values()])
subprocess.run([sys.executable,'-m','unittest','discover','-s','tests','-v'],check=True)
# Integration tests exercise persistence with the real SQLite native driver.
tests=compile_module('StorageTests',[storage],processor=False,sources=sorted((ROOT/'Storage/src/test/java').rglob('*.java')))
java=str(pathlib.Path(JAVAC).with_name('java'))
subprocess.run([java,'-cp',os.pathsep.join(map(str,[tests,storage,*LIBS])),'network.palace.storage.SQLiteStoreTest'],check=True)

OUTPUT=pathlib.Path(os.environ.get('PALACE_BUILD_OUTPUT',str(ROOT/'dist')))
OUTPUT.mkdir(parents=True,exist_ok=True)
# Server APIs and compile-only contracts are never shipped as runtime replacements.
provided=('spigot-api','bungeecord-','ProtocolLib','worldedit-','worldguard-','PlotSquared','BKCommonLib','TrainCarts','HolographicDisplays','anvilgui','lombok','annotations-','jsr305','guava-','snakeyaml-','gson-')
runtime=[p for p in LIBS if not p.name.startswith(provided)]
def package(name,classes,roots=(),jars=()):
 entries={};services={}
 def add(path,data):
  upper=path.upper()
  if path.endswith('/') or upper=='META-INF/MANIFEST.MF' or upper.endswith(('.SF','.RSA','.DSA')) or path=='META-INF/INDEX.LIST' or path.endswith('module-info.class'):return
  if path=='META-INF/services/javax.annotation.processing.Processor':return
  if path.startswith('META-INF/services/'):
   services.setdefault(path,set()).update(line for line in data.decode('utf-8').splitlines() if line and not line.startswith('#'))
  else:entries.setdefault(path,data)
 for root in [classes,*roots]:
  for file in sorted(root.rglob('*')):
   if file.is_file():add(file.relative_to(root).as_posix(),file.read_bytes())
 for library in jars:
  with zipfile.ZipFile(library) as archive:
   for path in sorted(archive.namelist()):add(path,archive.read(path))
 for license in sorted((ROOT/'vendor/licenses').glob('*')):
  if license.is_file():entries['META-INF/licenses/'+license.name]=license.read_bytes()
 entries['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n'
 for path,providers in services.items():entries[path]=('\n'.join(sorted(providers))+'\n').encode()
 for path in entries:
  if path.startswith(('network/palace/audio/','network/palace/cosmetics/','org/bukkit/','com/plotsquared/')):raise RuntimeError('Compile API leaked into runtime JAR: '+path)
 target=OUTPUT/(name+'.jar')
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
  for path,data in sorted(entries.items()):
   entry=zipfile.ZipInfo(path,(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;entry.external_attr=0o644<<16;archive.writestr(entry,data)
 validate_public_plugin(target)
 print('Packaged '+str(target),flush=True)
package('Core',core,[storage],runtime)
for name,classes in modules.items():
 extra=[storage] if name=='PalaceBungee' else []
 libraries=runtime if name=='PalaceBungee' else ([p for p in LIBS if p.name.startswith(('okhttp-','okio-','kotlin-stdlib-'))] if name=='Photopass' else [p for p in LIBS if p.name=='anvilgui.jar'] if name=='Creative' else [])
 package(name,classes,extra,libraries)
# Prove that the packaged Core contains SQLite and its dependencies.
subprocess.run([java,'-cp',os.pathsep.join(map(str,[tests,OUTPUT/'Core.jar'])),'network.palace.storage.SQLiteStoreTest'],check=True)
# Repeat config and messaging tests against the packaged Core and its defaults.
for test in ['network.palace.core.config.ConfigurationLoadingTest','network.palace.core.messagequeue.MessageHandlerTest','network.palace.core.mongo.ParkJoinDataTest']:
 subprocess.run([java,'-cp',os.pathsep.join(map(str,[core_tests,OUTPUT/'Core.jar',*[p for p in LIBS if p.name.startswith(provided)]])),test],check=True)
print('Built all eleven plugins offline.',flush=True)

(OUTPUT/'INSTALL.txt').write_text(
 'Install Core, Lobby, ParkManager, ParkWarp, Photopass, Pictify, RideManager, Show and Vanish on the Bukkit/Paper server, with their public runtime dependencies.\n'
 'ParkManager needs WorldEdit and WorldGuard; Show also uses WorldEdit for its building tools.\n'
 'Install PalaceBungee.jar only on a BungeeCord-compatible proxy, never on Paper/Spigot.\n'
 'Creative.jar targets Minecraft 1.16 with compatible PlotSquared v5 and WorldEdit 7; it is not compatible with Paper 1.12.2.\n'
 'Audio and Cosmetics are not distributed. Audio cues and cosmetic menus are disabled in this public build.\n', encoding='utf-8')

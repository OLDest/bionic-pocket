import pathlib,os,subprocess
root=pathlib.Path(__file__).resolve().parent
default=root.parents[1]/'2026-11-15'/'go-https-pikabu-ru-story-yandeks'/'yago'/'.tools'/'jdk'
jdk=pathlib.Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else next(default.glob('jdk-*'))
rtk=os.environ.get('RTK',r'C:\Users\OLDest\.local\bin\rtk.exe')
out=root/'build'/'markdown-test';out.mkdir(parents=True,exist_ok=True)
subprocess.run([rtk,'proxy',str(jdk/'bin'/'javac.exe'),'-encoding','UTF-8','-d',str(out),str(root/'android/java/ru/oldest/bionicpocket/MarkdownParser.java'),str(root/'android/test/MarkdownParserTest.java')],check=True)
subprocess.run([rtk,'proxy',str(jdk/'bin'/'java.exe'),'-cp',str(out),'MarkdownParserTest'],check=True)

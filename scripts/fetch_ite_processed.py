#!/usr/bin/env python3
"""Fetch selected processed ITE English members by HTTP ranges, checking CRC/SHA.

No full 7.3 GB archive or original 65 GB raw log is downloaded. Output members
are new files only. This acquires public study data, not protected local splits.
"""
import argparse,hashlib,json,struct,urllib.request,zlib
from pathlib import Path
URL='https://zenodo.org/records/12528163/files/ite_typing_dataset.zip?download=1'
MEMBERS=['processed2020/english/participants_labeled.csv','processed2020/english/test_sections_labeled.csv','processed2020/english/log_data_labeled.csv']

def get(start,end):
 response=urllib.request.urlopen(urllib.request.Request(URL,headers={'Range':f'bytes={start}-{end}'}),timeout=120)
 if response.status!=206 or response.headers.get('Content-Range','').split('/')[0]!=f'bytes {start}-{end}':response.close();raise ValueError('Server did not honor exact byte range')
 return response

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--index',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();index=json.load(open(a.index));a.output.mkdir(parents=True,exist_ok=True);manifest={'source':URL,'index_sha256':hashlib.sha256(a.index.read_bytes()).hexdigest(),'members':{}}
 for name in MEMBERS:
  entry=next(r for r in index if r['filename']==name);target=a.output/Path(name).name
  if target.exists():raise ValueError('Refusing to replace existing file: '+str(target))
  with get(entry['header_offset'],entry['header_offset']+29) as stream:header=stream.read()
  assert header[:4]==b'PK\x03\x04';n,extra=struct.unpack_from('<HH',header,26);start=entry['header_offset']+30+n+extra;end=start+entry['compressed']-1
  assert entry['method']==8;decoder=zlib.decompressobj(-15);crc=0;size=0;digest=hashlib.sha256();part=target.with_suffix('.csv.part');print('FETCH',name,'compressed',entry['compressed'],flush=True)
  with get(start,end) as stream,part.open('wb') as output:
   while block:=stream.read(1024*1024):
    data=decoder.decompress(block);output.write(data);crc=zlib.crc32(data,crc);size+=len(data);digest.update(data)
   data=decoder.flush();output.write(data);crc=zlib.crc32(data,crc);size+=len(data);digest.update(data)
  assert decoder.eof and size==entry['size'] and crc==entry['crc'],(name,size,crc)
  part.rename(target);manifest['members'][name]=dict(path=str(target),bytes=size,crc32=crc,sha256=digest.hexdigest());(a.output/'intake.json').write_text(json.dumps(manifest,indent=2)+'\n');print('VERIFIED',target,size,digest.hexdigest(),flush=True)
if __name__=='__main__':main()

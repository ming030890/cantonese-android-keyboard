"""Explicit compact BERT encoder for local training and native-port parity."""
import math
import torch
from torch import nn
from torch.nn import functional as F

class BertBlock(nn.Module):
    def __init__(self):
        super().__init__()
        self.q=nn.Linear(128,128);self.k=nn.Linear(128,128);self.v=nn.Linear(128,128)
        self.attn_out=nn.Linear(128,128);self.attn_norm=nn.LayerNorm(128,eps=1e-12)
        self.inter=nn.Linear(128,512);self.out=nn.Linear(512,128);self.out_norm=nn.LayerNorm(128,eps=1e-12)
        self.drop=nn.Dropout(.1)
    def forward(self,x,mask):
        n,l,_=x.shape
        shape=lambda y:y.view(n,l,2,64).transpose(1,2)
        q,k,v=shape(self.q(x)),shape(self.k(x)),shape(self.v(x))
        scores=(q@k.transpose(-2,-1))/8.;scores=scores.masked_fill(~mask[:,None,None,:],-10000.)
        attention=self.drop(F.softmax(scores,-1));h=(attention@v).transpose(1,2).contiguous().view(n,l,128)
        x=self.attn_norm(x+self.drop(self.attn_out(h)))
        return self.out_norm(x+self.drop(self.out(F.gelu(self.inter(x)))))

class BertTiny(nn.Module):
    def __init__(self):
        super().__init__();self.word=nn.Embedding(30522,128,padding_idx=0);self.position=nn.Embedding(512,128);self.segment=nn.Embedding(2,128);self.norm=nn.LayerNorm(128,eps=1e-12);self.drop=nn.Dropout(.1);self.blocks=nn.ModuleList([BertBlock(),BertBlock()]);self.pool=nn.Linear(128,128)
    def forward(self,ids,segments,mask):
        x=self.drop(self.norm(self.word(ids)+self.position(torch.arange(ids.shape[1],device=ids.device))[None]+self.segment(segments)))
        for layer in self.blocks:x=layer(x,mask)
        return torch.tanh(self.pool(x[:,0]))
    def load_google(self,checkpoint):
        from safetensors.torch import load_file
        p=load_file(str(checkpoint));mapping={'word':'embeddings.word_embeddings','position':'embeddings.position_embeddings','segment':'embeddings.token_type_embeddings','norm':'embeddings.LayerNorm','pool':'pooler.dense'}
        for i in range(2):
            for ours,theirs in [('q','attention.self.query'),('k','attention.self.key'),('v','attention.self.value'),('attn_out','attention.output.dense'),('attn_norm','attention.output.LayerNorm'),('inter','intermediate.dense'),('out','output.dense'),('out_norm','output.LayerNorm')]:mapping[f'blocks.{i}.{ours}']=f'encoder.layer.{i}.{theirs}'
        state={name:p['bert.'+theirs+'.'+suffix] for ours,theirs in mapping.items() for suffix in ('weight','bias') for name in [ours+'.'+suffix] if 'bert.'+theirs+'.'+suffix in p}
        self.load_state_dict(state,strict=True)

class PairRanker(nn.Module):
    def __init__(self,numeric_features=27):
        super().__init__();self.numeric_features=numeric_features;self.bert=BertTiny();self.head=nn.Linear(128+numeric_features,64);self.output=nn.Linear(64,1);self.drop=nn.Dropout(.1);nn.init.zeros_(self.output.weight);nn.init.zeros_(self.output.bias)
    def forward(self,ids,segments,mask,numeric,base):
        n,k,l=ids.shape;h=self.bert(ids.reshape(-1,l),segments.reshape(-1,l),mask.reshape(-1,l));h=self.drop(F.relu(self.head(torch.cat([h,numeric.reshape(-1,self.numeric_features)],1))));raw=self.output(h).reshape(n,k);delta=4*torch.tanh((raw-raw[:,:1])/4);return base+delta,delta

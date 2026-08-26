# Monografia NanoFaaS
PDF = nanofaas.pdf

all: $(PDF)

$(PDF): nanofaas.tex preambolo.tex bibliografia.bib $(wildcard cap/*.tex) $(wildcard figures/*.tex)
	latexmk -pdf -interaction=nonstopmode -halt-on-error nanofaas.tex

watch:
	latexmk -pdf -pvc -interaction=nonstopmode nanofaas.tex

clean:
	latexmk -C nanofaas.tex

.PHONY: all watch clean

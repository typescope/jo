set nomore
set encoding=utf-8
let fixtures = json_decode(join(readfile($JO_HIGHLIGHT_FIXTURES), "\n"))
let syntax_file = expand('<sfile>:p:h') . '/../../../tools/vim/syntax/jo.vim'

for fixture in fixtures
  enew!
  call setline(1, split(fixture.source, "\n", 1))
  syntax clear
  unlet! b:current_syntax
  execute 'source ' . fnameescape(syntax_file)
  syntax sync fromstart

  for check in fixture.checks
    let start = stridx(getline(check.line), check.text)
    call assert_true(start >= 0, fixture.name)
    let column = start + get(check, 'offset', 0) + 1
    let groups = map(synstack(check.line, column), 'synIDattr(v:val, "name")')
    let actual = index(groups, 'joBlockComment') >= 0 || index(groups, 'joLineComment') >= 0 ? 'comment'
      \ : index(groups, 'joString') >= 0 || index(groups, 'joMultiStr') >= 0 || index(groups, 'joChar') >= 0 ? 'string' : 'code'
    call assert_equal(check.kind, actual, fixture.name . ':' . check.line . ': ' . check.text)
  endfor
endfor

if !empty(v:errors)
  call writefile(v:errors, $JO_HIGHLIGHT_ERRORS)
  cquit
endif
qa!

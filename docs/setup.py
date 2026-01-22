from setuptools import setup

setup(
    name='jo-pygments',
    version='0.1.0',
    py_modules=['jo_lexer'],
    entry_points={
        'pygments.lexers': [
            'jo = jo_lexer:JoLexer',
        ],
    },
    install_requires=['pygments'],
)
